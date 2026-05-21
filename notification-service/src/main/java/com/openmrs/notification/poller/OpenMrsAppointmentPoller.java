package com.openmrs.notification.poller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openmrs.notification.config.RestTemplateFactory;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.outbox.OutboxService;
import com.openmrs.notification.service.PersonContactService;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantContext;
import com.openmrs.notification.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Polls the OpenMRS Appointment REST v1 API for every active tenant.
 *
 * Each tenant has its own OpenMRS host, credentials, and watermark cursor.
 * TenantContext is set per tenant iteration and cleared in a finally block.
 */
@Component
public class OpenMrsAppointmentPoller {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsAppointmentPoller.class);

    private static final String EXCHANGE               = "openmrs.events";
    private static final int    CIRCUIT_OPEN_THRESHOLD = 5;
    private static final long   CIRCUIT_OPEN_WAIT_MS   = 120_000;
    private static final int    POLL_WINDOW_HOURS       = 720;

    private final RestTemplateFactory  restTemplateFactory;
    private final RabbitTemplate       rabbitTemplate;
    private final JdbcTemplate         jdbc;
    private final OutboxService        outboxService;
    private final PersonContactService personContactService;
    private final TenantService        tenantService;

    // Circuit breaker state per tenant (keyed by tenant slug)
    private final java.util.concurrent.ConcurrentHashMap<String, int[]>  consecutiveFailures = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> circuitOpenedAt     = new java.util.concurrent.ConcurrentHashMap<>();

    public OpenMrsAppointmentPoller(
            RestTemplateFactory restTemplateFactory,
            RabbitTemplate rabbitTemplate,
            JdbcTemplate jdbc,
            OutboxService outboxService,
            PersonContactService personContactService,
            TenantService tenantService) {
        this.restTemplateFactory  = restTemplateFactory;
        this.rabbitTemplate        = rabbitTemplate;
        this.jdbc                  = jdbc;
        this.outboxService         = outboxService;
        this.personContactService  = personContactService;
        this.tenantService         = tenantService;
    }

    @Scheduled(fixedDelayString = "${poller.interval.fixed-delay-ms:120000}",
               initialDelayString = "${poller.interval.initial-delay-ms:30000}")
    public void poll() {
        List<Tenant> tenants = tenantService.getActiveTenants();
        if (tenants.isEmpty()) {
            log.debug("Poller: no active tenants — skipping");
            return;
        }
        for (Tenant tenant : tenants) {
            TenantContext.set(tenant);
            try {
                pollForTenant(tenant);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void pollForTenant(Tenant tenant) {
        String slug = tenant.getSlug();

        if (isCircuitOpen(slug)) {
            log.warn("Circuit OPEN for tenant={} — skipping poll", slug);
            return;
        }

        Instant now       = Instant.now();
        Instant windowEnd = now.plus(POLL_WINDOW_HOURS, ChronoUnit.HOURS);
        log.info("Polling OpenMRS for tenant={} window {} → {}", slug, now, windowEnd);

        String password = tenantService.decryptOpenmrsPassword(tenant);
        RestTemplate rt = restTemplateFactory.buildForTenant(tenant, password);

        List<RestAppointment> appointments;
        try {
            appointments = fetchAppointments(rt, tenant.getOpenmrsHost(), now, windowEnd);
            resetCircuit(slug);
        } catch (Exception ex) {
            incrementFailure(slug);
            log.error("OpenMRS poll failed for tenant={} (attempt #{})",
                    slug, getFailures(slug), ex);
            return;
        }

        if (appointments.isEmpty()) {
            log.debug("No appointments in window for tenant={}", slug);
            return;
        }

        log.info("Found {} appointment(s) for tenant={}", appointments.size(), slug);
        int queued = 0;
        for (RestAppointment apt : appointments) {
            try {
                String currentStatus = apt.getStatus();
                String seenStatus    = getSeenStatus(apt.getUuid(), tenant.getId());

                if (seenStatus != null && seenStatus.equalsIgnoreCase(currentStatus)) continue;

                enrichComments(rt, apt, tenant.getOpenmrsHost());

                AppointmentEvent event = toEvent(apt, seenStatus, tenant);
                outboxService.writePending(event);
                markSeen(apt.getUuid(), tenant.getId(), currentStatus);

                String routingKey = resolveRoutingKey(currentStatus);
                rabbitTemplate.convertAndSend(EXCHANGE, routingKey, event);
                outboxService.markPublished(apt.getUuid(), tenant.getId());
                queued++;

            } catch (Exception ex) {
                log.error("Failed to queue appointment uuid={} tenant={}", apt.getUuid(), slug, ex);
            }
        }
        log.info("Poll complete tenant={} — queued {}/{}", slug, queued, appointments.size());
    }

    // ── Comments enrichment ──────────────────────────────────────────────────

    @SuppressWarnings("rawtypes")
    private void enrichComments(RestTemplate rt, RestAppointment apt, String openmrsHost) {
        if (apt.getUuid() == null) return;
        try {
            String url = openmrsHost + "/ws/rest/v1/appointment?uuid=" + apt.getUuid();
            ResponseEntity<Map> resp = rt.getForEntity(url, Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Object comments = resp.getBody().get("comments");
                if (comments instanceof String s && !s.isBlank()) apt.setComments(s);
            }
        } catch (Exception ex) {
            log.warn("[Comments] Could not fetch for uuid={}: {}", apt.getUuid(), ex.getMessage());
        }
    }

    // ── OpenMRS REST v1 appointment/search ───────────────────────────────────

    private List<RestAppointment> fetchAppointments(RestTemplate rt, String openmrsHost,
                                                     Instant from, Instant to) {
        String url = openmrsHost + "/ws/rest/v1/appointment/search";
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC);

        Map<String, String> body = Map.of(
                "startDate", fmt.format(from),
                "endDate",   fmt.format(to));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<List<Map<String, Object>>> response = rt.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<>() {});

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null)
                throw new RuntimeException("OpenMRS returned HTTP " + response.getStatusCode());

            return response.getBody().stream().map(this::mapToRestAppointment).toList();

        } catch (HttpClientErrorException.Unauthorized e) {
            throw new RuntimeException("OpenMRS auth failed for this tenant", e);
        } catch (ResourceAccessException e) {
            throw new RuntimeException("OpenMRS unreachable: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private RestAppointment mapToRestAppointment(Map<String, Object> raw) {
        RestAppointment apt = new RestAppointment();
        apt.setUuid((String) raw.get("uuid"));
        apt.setStatus((String) raw.get("status"));

        Object start = raw.get("startDateTime");
        if (start instanceof Number) apt.setStartDateTime(Instant.ofEpochMilli(((Number) start).longValue()));

        Map<String, Object> patient = (Map<String, Object>) raw.get("patient");
        if (patient != null) {
            apt.setPatientUuid((String) patient.get("uuid"));
            apt.setPatientName((String) patient.get("name"));
        }
        Map<String, Object> location = (Map<String, Object>) raw.get("location");
        if (location != null) apt.setLocationName((String) location.get("name"));

        apt.setComments((String) raw.get("comments"));
        return apt;
    }

    // ── Event mapping ────────────────────────────────────────────────────────

    private AppointmentEvent toEvent(RestAppointment apt, String previousStatus, Tenant tenant) {
        AppointmentEvent event = new AppointmentEvent();
        event.setTenantId(tenant.getId());
        event.setAppointmentUuid(apt.getUuid());
        event.setPatientUuid(apt.getPatientUuid());
        event.setPatientName(apt.getPatientName());
        event.setLocationName(apt.getLocationName());
        event.setAppointmentTime(apt.getStartDateTime());
        event.setComments(apt.getComments());
        event.setOccurredAt(Instant.now());
        event.setEventType(statusToEventType(apt.getStatus(), previousStatus));
        personContactService.enrichEvent(event);
        return event;
    }

    private AppointmentEvent.EventType statusToEventType(String current, String previous) {
        if (current == null) return AppointmentEvent.EventType.SCHEDULED;
        return switch (current.toLowerCase()) {
            case "cancelled", "missed" -> AppointmentEvent.EventType.CANCELLED;
            case "scheduled" -> previous == null
                    ? AppointmentEvent.EventType.SCHEDULED
                    : AppointmentEvent.EventType.UPDATED;
            default -> AppointmentEvent.EventType.UPDATED;
        };
    }

    private String resolveRoutingKey(String status) {
        if (status == null) return "appointment.scheduled";
        return switch (status.toLowerCase()) {
            case "cancelled", "missed" -> "appointment.cancelled";
            case "scheduled"           -> "appointment.scheduled";
            default                    -> "appointment.updated";
        };
    }

    // ── Duplicate / change guard ─────────────────────────────────────────────

    private String getSeenStatus(String appointmentUuid, java.util.UUID tenantId) {
        try {
            return jdbc.queryForObject(
                    "SELECT openmrs_status FROM seen_appointments WHERE appointment_uuid = ? AND tenant_id = ?",
                    String.class, appointmentUuid, tenantId);
        } catch (Exception e) { return null; }
    }

    private void markSeen(String appointmentUuid, java.util.UUID tenantId, String status) {
        jdbc.update("""
            INSERT INTO seen_appointments (appointment_uuid, tenant_id, openmrs_status, queued_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (appointment_uuid, tenant_id) DO UPDATE
              SET openmrs_status = EXCLUDED.openmrs_status, queued_at = now()
            """, appointmentUuid, tenantId, status);
    }

    // ── Circuit breaker ──────────────────────────────────────────────────────

    private boolean isCircuitOpen(String slug) {
        int failures = getFailures(slug);
        if (failures < CIRCUIT_OPEN_THRESHOLD) return false;
        long opened  = circuitOpenedAt.getOrDefault(slug, new long[]{0})[0];
        if (System.currentTimeMillis() - opened >= CIRCUIT_OPEN_WAIT_MS) {
            consecutiveFailures.put(slug, new int[]{CIRCUIT_OPEN_THRESHOLD - 1});
            return false;
        }
        return true;
    }

    private int getFailures(String slug) {
        return consecutiveFailures.getOrDefault(slug, new int[]{0})[0];
    }

    private void incrementFailure(String slug) {
        int next = getFailures(slug) + 1;
        consecutiveFailures.put(slug, new int[]{next});
        if (next >= CIRCUIT_OPEN_THRESHOLD) {
            circuitOpenedAt.put(slug, new long[]{System.currentTimeMillis()});
            log.error("CIRCUIT OPENED for tenant={} after {} failures", slug, next);
        }
    }

    private void resetCircuit(String slug) {
        consecutiveFailures.put(slug, new int[]{0});
    }

    // ── REST v1 Appointment POJO ─────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RestAppointment {
        private String  uuid;
        private String  status;
        private Instant startDateTime;
        private String  patientUuid;
        private String  patientName;
        private String  locationName;
        private String  comments;

        public String  getUuid()            { return uuid; }
        public void    setUuid(String v)    { this.uuid = v; }
        public String  getStatus()          { return status; }
        public void    setStatus(String v)  { this.status = v; }
        public Instant getStartDateTime()             { return startDateTime; }
        public void    setStartDateTime(Instant v)    { this.startDateTime = v; }
        public String  getPatientUuid()               { return patientUuid; }
        public void    setPatientUuid(String v)       { this.patientUuid = v; }
        public String  getPatientName()               { return patientName; }
        public void    setPatientName(String v)       { this.patientName = v; }
        public String  getLocationName()              { return locationName; }
        public void    setLocationName(String v)      { this.locationName = v; }
        public String  getComments()                  { return comments; }
        public void    setComments(String v)          { this.comments = v; }
    }
}

package com.openmrs.notification.poller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.outbox.OutboxService;
import com.openmrs.notification.service.PersonContactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
 * Polls the OpenMRS Appointment REST v1 API on a fixed schedule.
 *
 * NOTE: The FHIR2 Appointment resource is NOT supported by this OpenMRS
 * installation (the fhir2 module does not include Appointment mapping).
 * We use POST /ws/rest/v1/appointment/search with a sliding 48-hour window
 * instead. The AppointmentReconciler uses a different window for catch-up.
 *
 * Resilience design:
 * ─────────────────
 * 1. Sliding window — polls the next 48 hours from now. Catches new and
 * recently-updated appointments within that window.
 *
 * 2. Duplicate guard — before publishing to RabbitMQ we check whether the
 * appointment UUID was already queued (seen_appointments table). Status
 * changes (Scheduled → Cancelled) trigger a re-queue with CANCELLED type.
 *
 * 3. Circuit breaker (manual) — after CIRCUIT_OPEN_THRESHOLD consecutive
 * OpenMRS failures we back off for CIRCUIT_OPEN_WAIT_MS and log an alert.
 * When OpenMRS recovers the circuit resets automatically.
 *
 * 4. Never-advance-on-failure — watermark only moves forward after ALL
 * fetched appointments have been processed without error.
 *
 * 5. All fetched appointments are written to Postgres BEFORE being published
 * to RabbitMQ. If the broker is down, we have the data and will retry.
 *
 * Data flow:
 * [Scheduler] → POST /ws/rest/v1/appointment/search (next 48h)
 * → for each appointment: check seen_appointments for status change
 * → new or changed: save to seen_appointments + outbox
 * → publish AppointmentEvent to RabbitMQ exchange
 */
@Component
public class OpenMrsAppointmentPoller {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsAppointmentPoller.class);

    private static final String EXCHANGE = "openmrs.events";
    private static final int CIRCUIT_OPEN_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_WAIT_MS = 120_000; // 2 min

    /**
     * How far ahead to search for appointments (hours).
     * 30 days — ensures patients get a confirmation immediately when
     * an appointment is created, not only 48h before it occurs.
     */
    private static final int POLL_WINDOW_HOURS = 720;

    private final RestTemplate restTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final JdbcTemplate jdbc;
    private final OutboxService outboxService;
    private final PersonContactService personContactService;
    private final String openmrsBaseUrl;

    // Circuit breaker state (in-memory — resets on restart, which is fine)
    private int consecutiveFailures = 0;
    private long circuitOpenedAt = 0;

    public OpenMrsAppointmentPoller(
            @Qualifier("openmrsRestTemplate") RestTemplate restTemplate,
            RabbitTemplate rabbitTemplate,
            JdbcTemplate jdbc,
            OutboxService outboxService,
            PersonContactService personContactService,
            @Value("${openmrs.base-url:http://gateway/openmrs}") String openmrsBaseUrl) {
        this.restTemplate = restTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.jdbc = jdbc;
        this.outboxService = outboxService;
        this.personContactService = personContactService;
        this.openmrsBaseUrl = openmrsBaseUrl;
    }

    /**
     * Main polling loop. Runs every 2 minutes by default.
     * Adjust via: poller.interval.fixed-delay-ms=120000
     */
    @Scheduled(fixedDelayString = "${poller.interval.fixed-delay-ms:120000}", initialDelayString = "${poller.interval.initial-delay-ms:30000}")
    public void poll() {

        // ── Circuit breaker check ─────────────────────────────────────────
        if (isCircuitOpen()) {
            log.warn("Circuit OPEN — skipping poll. OpenMRS unreachable for {} consecutive attempts.",
                    consecutiveFailures);
            return;
        }

        Instant now = Instant.now();
        Instant windowEnd = now.plus(POLL_WINDOW_HOURS, ChronoUnit.HOURS);
        log.info("Polling OpenMRS appointments {} → {}", now, windowEnd);

        List<RestAppointment> appointments;
        try {
            appointments = fetchAppointments(now, windowEnd);
            consecutiveFailures = 0; // success → reset circuit
        } catch (Exception ex) {
            consecutiveFailures++;
            log.error("OpenMRS poll failed (attempt #{}) — will retry next interval.",
                    consecutiveFailures, ex);
            if (consecutiveFailures >= CIRCUIT_OPEN_THRESHOLD) {
                circuitOpenedAt = System.currentTimeMillis();
                log.error("CIRCUIT OPENED — OpenMRS unreachable after {} attempts. Backing off {}s.",
                        consecutiveFailures, CIRCUIT_OPEN_WAIT_MS / 1000);
            }
            return;
        }

        if (appointments.isEmpty()) {
            log.debug("No appointments in the next {} hours.", POLL_WINDOW_HOURS);
            return;
        }

        log.info("Found {} appointment(s) in polling window", appointments.size());

        int queued = 0;
        for (RestAppointment apt : appointments) {
            try {
                String currentStatus = apt.getStatus();
                String seenStatus = getSeenStatus(apt.getUuid());

                // Skip if already seen with the same status
                if (seenStatus != null && seenStatus.equalsIgnoreCase(currentStatus)) {
                    log.debug("Skipping unchanged appointment uuid={} status={}", apt.getUuid(), currentStatus);
                    continue;
                }

                AppointmentEvent event = toEvent(apt, seenStatus);
                outboxService.writePending(event);
                markSeen(apt.getUuid(), currentStatus);

                String routingKey = resolveRoutingKey(currentStatus);
                rabbitTemplate.convertAndSend(EXCHANGE, routingKey, event);
                outboxService.markPublished(apt.getUuid());
                queued++;

                log.info("Queued appointment uuid={} status={} routingKey={}",
                        apt.getUuid(), currentStatus, routingKey);

            } catch (Exception ex) {
                log.error("Failed to queue appointment uuid={} — will retry on next poll",
                        apt.getUuid(), ex);
            }
        }

        log.info("Poll complete — queued {}/{} appointments", queued, appointments.size());
    }

    // ── OpenMRS REST v1 appointment/search ───────────────────────────────────

    private List<RestAppointment> fetchAppointments(Instant from, Instant to) {
        String url = openmrsBaseUrl + "/ws/rest/v1/appointment/search";

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC);

        Map<String, String> body = Map.of(
                "startDate", fmt.format(from),
                "endDate", fmt.format(to));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<>() {
                    });

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("OpenMRS returned HTTP " + response.getStatusCode());
            }

            List<Map<String, Object>> raw = response.getBody();
            return raw.stream().map(this::mapToRestAppointment).toList();

        } catch (HttpClientErrorException.Unauthorized e) {
            throw new RuntimeException("OpenMRS auth failed — check OPENMRS_API_USER/PASSWORD", e);
        } catch (ResourceAccessException e) {
            throw new RuntimeException("OpenMRS unreachable: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private RestAppointment mapToRestAppointment(Map<String, Object> raw) {
        RestAppointment apt = new RestAppointment();
        apt.setUuid((String) raw.get("uuid"));
        apt.setStatus((String) raw.get("status"));

        // startDateTime is a Unix timestamp in milliseconds
        Object start = raw.get("startDateTime");
        if (start instanceof Number) {
            apt.setStartDateTime(Instant.ofEpochMilli(((Number) start).longValue()));
        }

        // Patient details
        Map<String, Object> patient = (Map<String, Object>) raw.get("patient");
        if (patient != null) {
            apt.setPatientUuid((String) patient.get("uuid"));
            apt.setPatientName((String) patient.get("name"));
        }

        // Location
        Map<String, Object> location = (Map<String, Object>) raw.get("location");
        if (location != null) {
            apt.setLocationName((String) location.get("name"));
        }

        // Comments
        apt.setComments((String) raw.get("comments"));

        return apt;
    }

    // ── Event mapping ────────────────────────────────────────────────────────

    private AppointmentEvent toEvent(RestAppointment apt, String previousStatus) {
        AppointmentEvent event = new AppointmentEvent();
        event.setAppointmentUuid(apt.getUuid());
        event.setPatientUuid(apt.getPatientUuid());
        event.setPatientName(apt.getPatientName());
        event.setLocationName(apt.getLocationName());
        event.setAppointmentTime(apt.getStartDateTime());
        event.setOccurredAt(Instant.now());
        event.setEventType(statusToEventType(apt.getStatus(), previousStatus));

        // Fase 2: contactgegevens ophalen via GET /ws/rest/v1/person/{uuid}?v=full
        personContactService.enrichEvent(event);

        return event;
    }

    private AppointmentEvent.EventType statusToEventType(String current, String previous) {
        if (current == null)
            return AppointmentEvent.EventType.SCHEDULED;
        return switch (current.toLowerCase()) {
            case "cancelled", "missed" -> AppointmentEvent.EventType.CANCELLED;
            case "scheduled" -> previous == null
                    ? AppointmentEvent.EventType.SCHEDULED
                    : AppointmentEvent.EventType.UPDATED;
            default -> AppointmentEvent.EventType.UPDATED;
        };
    }

    private String resolveRoutingKey(String status) {
        if (status == null)
            return "appointment.scheduled";
        return switch (status.toLowerCase()) {
            case "cancelled", "missed" -> "appointment.cancelled";
            case "scheduled" -> "appointment.scheduled";
            default -> "appointment.updated";
        };
    }

    // ── Duplicate / change guard ─────────────────────────────────────────────

    /** Returns the previously seen status, or null if never seen. */
    private String getSeenStatus(String appointmentUuid) {
        try {
            return jdbc.queryForObject(
                    "SELECT openmrs_status FROM seen_appointments WHERE appointment_uuid = ?",
                    String.class, appointmentUuid);
        } catch (Exception e) {
            return null;
        }
    }

    private void markSeen(String appointmentUuid, String status) {
        jdbc.update("""
                INSERT INTO seen_appointments (appointment_uuid, openmrs_status, queued_at)
                VALUES (?, ?, now())
                ON CONFLICT (appointment_uuid) DO UPDATE
                  SET openmrs_status = EXCLUDED.openmrs_status, queued_at = now()
                """, appointmentUuid, status);
    }

    // ── Circuit breaker ──────────────────────────────────────────────────────

    private boolean isCircuitOpen() {
        if (consecutiveFailures < CIRCUIT_OPEN_THRESHOLD)
            return false;
        long elapsed = System.currentTimeMillis() - circuitOpenedAt;
        if (elapsed >= CIRCUIT_OPEN_WAIT_MS) {
            log.info("Circuit half-open — attempting recovery poll");
            consecutiveFailures = CIRCUIT_OPEN_THRESHOLD - 1;
            return false;
        }
        return true;
    }

    // ── REST v1 Appointment POJO ─────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RestAppointment {
        private String uuid;
        private String status;
        private Instant startDateTime;
        private String patientUuid;
        private String patientName;
        private String locationName;
        private String comments;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String v) {
            this.uuid = v;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String v) {
            this.status = v;
        }

        public Instant getStartDateTime() {
            return startDateTime;
        }

        public void setStartDateTime(Instant v) {
            this.startDateTime = v;
        }

        public String getPatientUuid() {
            return patientUuid;
        }

        public void setPatientUuid(String v) {
            this.patientUuid = v;
        }

        public String getPatientName() {
            return patientName;
        }

        public void setPatientName(String v) {
            this.patientName = v;
        }

        public String getLocationName() {
            return locationName;
        }

        public void setLocationName(String v) {
            this.locationName = v;
        }

        public String getComments() {
            return comments;
        }

        public void setComments(String v) {
            this.comments = v;
        }
    }
}

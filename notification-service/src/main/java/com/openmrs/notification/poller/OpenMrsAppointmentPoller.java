package com.openmrs.notification.poller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Polls the OpenMRS FHIR Appointment endpoint on a fixed schedule.
 *
 * Resilience design:
 * ─────────────────
 * 1. Watermark cursor — persisted in Postgres (sync_watermarks table).
 *    On startup we resume from the last successful cursor. If the service
 *    was down for hours, we automatically catch up.
 *
 * 2. Never-advance-on-failure — the watermark only moves forward after
 *    ALL fetched appointments have been queued. A partial failure leaves
 *    the cursor where it was so the next poll retries the same window.
 *
 * 3. Circuit breaker (manual) — after CIRCUIT_OPEN_THRESHOLD consecutive
 *    OpenMRS failures we back off for CIRCUIT_OPEN_WAIT_MS and log an alert.
 *    When OpenMRS recovers the circuit resets automatically.
 *
 * 4. Duplicate guard — before publishing to RabbitMQ we check whether the
 *    appointment was already queued (seen_appointments table). This prevents
 *    double-notification when a poll window overlaps.
 *
 * 5. All fetched appointments are written to Postgres BEFORE being published
 *    to RabbitMQ. If the broker is down, we have the data and will retry.
 *
 * Data flow:
 *   [Scheduler] → poll OpenMRS FHIR API
 *       → for each new appointment: save to seen_appointments
 *       → publish AppointmentEvent to RabbitMQ exchange
 *       → advance watermark
 */
@Component
public class OpenMrsAppointmentPoller {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsAppointmentPoller.class);

    private static final String RESOURCE = "appointment";
    private static final String EXCHANGE  = "openmrs.events";
    private static final int    CIRCUIT_OPEN_THRESHOLD = 5;
    private static final long   CIRCUIT_OPEN_WAIT_MS   = 120_000; // 2 min

    private final RestTemplate   restTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final JdbcTemplate   jdbc;
    private final OutboxService  outboxService;
    private final String         openmrsBaseUrl;

    // Circuit breaker state (in-memory — resets on restart, which is fine)
    private int  consecutiveFailures = 0;
    private long circuitOpenedAt     = 0;

    public OpenMrsAppointmentPoller(
            RestTemplate restTemplate,
            RabbitTemplate rabbitTemplate,
            JdbcTemplate jdbc,
            OutboxService outboxService,
            @Value("${openmrs.base-url:http://openmrs-gateway/openmrs}") String openmrsBaseUrl) {
        this.restTemplate   = restTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.jdbc           = jdbc;
        this.outboxService  = outboxService;
        this.openmrsBaseUrl = openmrsBaseUrl;
    }

    /**
     * Main polling loop. Runs every 2 minutes by default.
     * Adjust via: poller.interval.fixed-delay-ms=120000
     */
    @Scheduled(fixedDelayString = "${poller.interval.fixed-delay-ms:120000}",
               initialDelayString = "${poller.interval.initial-delay-ms:30000}")
    public void poll() {

        // ── Circuit breaker check ─────────────────────────────────────────
        if (isCircuitOpen()) {
            log.warn("Circuit OPEN — skipping poll. OpenMRS unreachable for {} consecutive attempts.",
                    consecutiveFailures);
            return;
        }

        Instant since = readWatermark();
        log.info("Polling OpenMRS appointments since {}", since);

        List<FhirAppointment> appointments;
        try {
            appointments = fetchAppointments(since);
            consecutiveFailures = 0; // success → reset circuit
        } catch (Exception ex) {
            consecutiveFailures++;
            log.error("OpenMRS poll failed (attempt #{}) — watermark NOT advanced. Will retry.",
                    consecutiveFailures, ex);
            if (consecutiveFailures >= CIRCUIT_OPEN_THRESHOLD) {
                circuitOpenedAt = System.currentTimeMillis();
                log.error("CIRCUIT OPENED — OpenMRS unreachable after {} attempts. Backing off for {}s.",
                        consecutiveFailures, CIRCUIT_OPEN_WAIT_MS / 1000);
            }
            return; // watermark stays put → next run retries same window
        }

        if (appointments.isEmpty()) {
            log.debug("No new appointments since {}", since);
            advanceWatermark(Instant.now());
            return;
        }

        log.info("Found {} new/updated appointment(s)", appointments.size());

        int queued = 0;
        for (FhirAppointment apt : appointments) {
            try {
                if (alreadySeen(apt.getId())) {
                    log.debug("Skipping already-seen appointment uuid={}", apt.getId());
                    continue;
                }
                AppointmentEvent event = toEvent(apt);
                // Persist first — if RabbitMQ is down we still have the record
                outboxService.writePending(event);
                markSeen(apt.getId(), apt.getStatus());
                // Publish to broker
                String routingKey = resolveRoutingKey(apt.getStatus());
                rabbitTemplate.convertAndSend(EXCHANGE, routingKey, event);
                outboxService.markPublished(apt.getId());
                queued++;
                log.info("Queued appointment uuid={} status={} routingKey={}", apt.getId(), apt.getStatus(), routingKey);
            } catch (Exception ex) {
                log.error("Failed to queue appointment uuid={} — will retry on next poll", apt.getId(), ex);
                // Don't advance watermark — next poll will retry this appointment
            }
        }

        log.info("Poll complete — queued {}/{} appointments", queued, appointments.size());
        // Only advance watermark when we've processed everything without error
        if (queued == appointments.size()) {
            advanceWatermark(Instant.now());
        }
    }

    // ── OpenMRS FHIR API fetch ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<FhirAppointment> fetchAppointments(Instant since) {
        // OpenMRS FHIR R4 — Appointment resource with date filter
        // GET /openmrs/ws/fhir2/R4/Appointment?date=ge<ISO>&_sort=date&_count=200
        String url = openmrsBaseUrl
                + "/ws/fhir2/R4/Appointment"
                + "?date=ge" + DateTimeFormatter.ISO_INSTANT.format(since)
                + "&_sort=date&_count=200";

        log.debug("Fetching: {}", url);

        try {
            ResponseEntity<FhirBundle> response = restTemplate.getForEntity(url, FhirBundle.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("OpenMRS returned HTTP " + response.getStatusCode());
            }

            List<FhirBundle.Entry> entries = response.getBody().getEntry();
            if (entries == null) return List.of();

            return entries.stream()
                    .filter(e -> e.getResource() != null)
                    .map(FhirBundle.Entry::getResource)
                    .toList();

        } catch (HttpClientErrorException.Unauthorized e) {
            throw new RuntimeException("OpenMRS auth failed — check OPENMRS_API_USER/PASSWORD", e);
        } catch (ResourceAccessException e) {
            throw new RuntimeException("OpenMRS unreachable: " + e.getMessage(), e);
        }
    }

    // ── Watermark helpers ────────────────────────────────────────────────────

    private Instant readWatermark() {
        try {
            String cursor = jdbc.queryForObject(
                    "SELECT last_cursor FROM sync_watermarks WHERE resource_type = ?",
                    String.class, RESOURCE);
            return cursor != null ? Instant.parse(cursor) : Instant.now().minus(24, ChronoUnit.HOURS);
        } catch (Exception e) {
            log.warn("No watermark found for '{}' — defaulting to 24h ago", RESOURCE);
            return Instant.now().minus(24, ChronoUnit.HOURS);
        }
    }

    private void advanceWatermark(Instant to) {
        jdbc.update("""
            INSERT INTO sync_watermarks (resource_type, last_updated, last_cursor)
            VALUES (?, now(), ?)
            ON CONFLICT (resource_type) DO UPDATE
              SET last_updated = now(), last_cursor = EXCLUDED.last_cursor
            """, RESOURCE, DateTimeFormatter.ISO_INSTANT.format(to));
        log.debug("Watermark advanced to {}", to);
    }

    // ── Duplicate guard ──────────────────────────────────────────────────────

    private boolean alreadySeen(String appointmentUuid) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seen_appointments WHERE appointment_uuid = ?",
                Integer.class, appointmentUuid);
        return count != null && count > 0;
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
        if (consecutiveFailures < CIRCUIT_OPEN_THRESHOLD) return false;
        long elapsed = System.currentTimeMillis() - circuitOpenedAt;
        if (elapsed >= CIRCUIT_OPEN_WAIT_MS) {
            log.info("Circuit half-open — attempting recovery poll");
            consecutiveFailures = CIRCUIT_OPEN_THRESHOLD - 1; // allow one attempt
            return false;
        }
        return true;
    }

    // ── Event mapping ────────────────────────────────────────────────────────

    private AppointmentEvent toEvent(FhirAppointment apt) {
        AppointmentEvent event = new AppointmentEvent();
        event.setAppointmentUuid(apt.getId());
        event.setEventType(statusToEventType(apt.getStatus()));
        event.setOccurredAt(Instant.now());

        if (apt.getStart() != null) {
            try { event.setAppointmentTime(Instant.parse(apt.getStart())); }
            catch (Exception ignored) {}
        }

        // Extract patient reference from FHIR participant array
        if (apt.getParticipant() != null) {
            apt.getParticipant().stream()
               .filter(p -> p.getActor() != null && p.getActor().getReference() != null
                         && p.getActor().getReference().startsWith("Patient/"))
               .findFirst()
               .ifPresent(p -> {
                   String ref = p.getActor().getReference();
                   event.setPatientUuid(ref.replace("Patient/", ""));
                   event.setPatientName(p.getActor().getDisplay());
               });
        }

        return event;
    }

    private AppointmentEvent.EventType statusToEventType(String fhirStatus) {
        if (fhirStatus == null) return AppointmentEvent.EventType.SCHEDULED;
        return switch (fhirStatus.toLowerCase()) {
            case "cancelled", "noshow" -> AppointmentEvent.EventType.CANCELLED;
            case "booked"              -> AppointmentEvent.EventType.SCHEDULED;
            default                    -> AppointmentEvent.EventType.UPDATED;
        };
    }

    private String resolveRoutingKey(String fhirStatus) {
        if (fhirStatus == null) return "appointment.scheduled";
        return switch (fhirStatus.toLowerCase()) {
            case "cancelled", "noshow" -> "appointment.cancelled";
            case "booked"              -> "appointment.scheduled";
            default                    -> "appointment.updated";
        };
    }

    // ── FHIR POJO models (minimal — only fields we use) ──────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FhirBundle {
        private List<Entry> entry;
        public List<Entry> getEntry() { return entry; }
        public void setEntry(List<Entry> entry) { this.entry = entry; }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Entry {
            private FhirAppointment resource;
            public FhirAppointment getResource() { return resource; }
            public void setResource(FhirAppointment resource) { this.resource = resource; }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FhirAppointment {
        private String id;
        private String status;
        private String start;
        private List<Participant> participant;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getStart() { return start; }
        public void setStart(String start) { this.start = start; }
        public List<Participant> getParticipant() { return participant; }
        public void setParticipant(List<Participant> participant) { this.participant = participant; }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Participant {
            private Actor actor;
            public Actor getActor() { return actor; }
            public void setActor(Actor actor) { this.actor = actor; }

            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Actor {
                private String reference;
                private String display;
                public String getReference() { return reference; }
                public void setReference(String reference) { this.reference = reference; }
                public String getDisplay() { return display; }
                public void setDisplay(String display) { this.display = display; }
            }
        }
    }
}

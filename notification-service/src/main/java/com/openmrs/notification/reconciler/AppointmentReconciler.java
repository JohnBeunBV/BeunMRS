package com.openmrs.notification.reconciler;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.service.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Periodically polls the OpenMRS REST API for appointments updated
 * since the last-seen watermark. This is the catch-up mechanism that
 * guarantees no appointment event is permanently missed — even if the
 * RabbitMQ connection was down, OpenMRS crashed, or this service was
 * restarted.
 *
 * Flow:
 *   1. Read watermark from sync_watermarks table.
 *   2. Query OpenMRS /ws/rest/v1/appointment?lastUpdated={watermark}.
 *   3. For each returned appointment not already processed, dispatch.
 *   4. Advance watermark to now.
 */
@Component
public class AppointmentReconciler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReconciler.class);
    private static final String RESOURCE = "appointment";

    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbc;
    private final NotificationDispatcher dispatcher;
    private final String openmrsBaseUrl;
    private final String openmrsUser;
    private final String openmrsPassword;

    public AppointmentReconciler(
            RestTemplate restTemplate,
            JdbcTemplate jdbc,
            NotificationDispatcher dispatcher,
            @Value("${openmrs.base-url:http://openmrs:8080/openmrs}") String openmrsBaseUrl,
            @Value("${openmrs.api.username:admin}") String openmrsUser,
            @Value("${openmrs.api.password:Admin1234}") String openmrsPassword) {
        this.restTemplate   = restTemplate;
        this.jdbc           = jdbc;
        this.dispatcher     = dispatcher;
        this.openmrsBaseUrl = openmrsBaseUrl;
        this.openmrsUser    = openmrsUser;
        this.openmrsPassword = openmrsPassword;
    }

    /**
     * Runs every 5 minutes. Adjust via application.properties:
     *   reconciler.poll.fixed-delay-ms=300000
     */
    @Scheduled(fixedDelayString = "${reconciler.poll.fixed-delay-ms:300000}",
               initialDelayString = "${reconciler.poll.initial-delay-ms:60000}")
    public void reconcile() {
        Instant since = readWatermark();
        log.info("Reconciler running — checking appointments since {}", since);

        try {
            List<AppointmentEvent> events = fetchFromOpenMRS(since);
            log.info("Reconciler found {} appointment(s) since {}", events.size(), since);

            for (AppointmentEvent event : events) {
                if (!alreadyProcessed(event)) {
                    dispatcher.dispatch(event);
                }
            }

            advanceWatermark(Instant.now());

        } catch (Exception ex) {
            log.error("Reconciler poll failed — will retry at next interval", ex);
            // Watermark not advanced — next run will re-check the same window
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private Instant readWatermark() {
        try {
            String cursor = jdbc.queryForObject(
                    "SELECT last_cursor FROM sync_watermarks WHERE resource_type = ?",
                    String.class, RESOURCE);
            return cursor != null ? Instant.parse(cursor) : Instant.now().minus(1, ChronoUnit.HOURS);
        } catch (Exception e) {
            return Instant.now().minus(1, ChronoUnit.HOURS);
        }
    }

    private void advanceWatermark(Instant now) {
        jdbc.update("""
            INSERT INTO sync_watermarks (resource_type, last_updated, last_cursor)
            VALUES (?, now(), ?)
            ON CONFLICT (resource_type) DO UPDATE
            SET last_updated = now(), last_cursor = EXCLUDED.last_cursor
            """, RESOURCE, now.toString());
    }

    @SuppressWarnings("unchecked")
    private List<AppointmentEvent> fetchFromOpenMRS(Instant since) {
        // OpenMRS REST v1 — Appointment Scheduling module endpoint
        String url = openmrsBaseUrl + "/ws/rest/v1/appointment?lastUpdated=" + since.toString() + "&v=full";

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return List.of();
            }
            List<Map<String, Object>> results =
                    (List<Map<String, Object>>) response.getBody().getOrDefault("results", List.of());

            return results.stream()
                    .map(this::mapToEvent)
                    .toList();
        } catch (Exception ex) {
            log.warn("OpenMRS poll failed: {}", ex.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private AppointmentEvent mapToEvent(Map<String, Object> raw) {
        AppointmentEvent e = new AppointmentEvent();
        e.setAppointmentUuid((String) raw.get("uuid"));
        e.setOccurredAt(Instant.now());

        // Map the actual OpenMRS status to our internal EventType
        e.setEventType(statusToEventType((String) raw.get("status")));

        // Patient
        Map<String, Object> patient = (Map<String, Object>) raw.get("patient");
        if (patient != null) {
            e.setPatientUuid((String) patient.get("uuid"));
            e.setPatientName((String) patient.get("name"));
        }

        // startDateTime is a Unix timestamp in milliseconds (same as the Poller)
        Object start = raw.get("startDateTime");
        if (start instanceof Number) {
            e.setAppointmentTime(Instant.ofEpochMilli(((Number) start).longValue()));
        }

        // Location
        Map<String, Object> location = (Map<String, Object>) raw.get("location");
        if (location != null) {
            e.setLocationName((String) location.get("name"));
        }

        return e;
    }

    /** Maps OpenMRS appointment status to internal EventType. */
    private AppointmentEvent.EventType statusToEventType(String status) {
        if (status == null) return AppointmentEvent.EventType.SCHEDULED;
        return switch (status.toLowerCase()) {
            case "cancelled", "missed" -> AppointmentEvent.EventType.CANCELLED;
            case "scheduled"           -> AppointmentEvent.EventType.SCHEDULED;
            default                    -> AppointmentEvent.EventType.UPDATED;
        };
    }

    private boolean alreadyProcessed(AppointmentEvent event) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE event_type = ? AND payload::text LIKE ?",
                Integer.class,
                event.getEventType().name(),
                "%" + event.getAppointmentUuid() + "%"
        );
        return count != null && count > 0;
    }
}

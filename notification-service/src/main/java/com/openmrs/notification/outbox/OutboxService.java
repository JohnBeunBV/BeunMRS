package com.openmrs.notification.outbox;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Writes every notification attempt to the notification_log table.
 * Also manages the outbox_events table used by the relay loop for
 * guaranteed at-least-once delivery even through provider outages.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final JdbcTemplate jdbc;

    public OutboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record the outcome of a send attempt.
     */
    public void recordResult(AppointmentEvent event, String providerName, NotificationResult result) {
        try {
            jdbc.update("""
                INSERT INTO notification_log
                    (patient_uuid, channel, event_type, status, sent_at, error_message, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                event.getPatientUuid(),
                providerName,
                event.getEventType().name(),
                result.isSuccess() ? "sent" : "failed",
                result.isSuccess() ? Timestamp.from(Instant.now()) : null,
                result.getErrorMessage(),
                buildPayloadJson(event, result)
            );
        } catch (Exception ex) {
            // Log but don't rethrow — recording failure must not block the consumer
            log.error("Failed to record notification result for appointment={}",
                    event.getAppointmentUuid(), ex);
        }
    }

    /**
     * Write a pending outbox entry — called before the send attempt so
     * we can replay it if the process crashes mid-flight.
     */
    public void writePending(AppointmentEvent event) {
        jdbc.update("""
            INSERT INTO outbox_events
                (aggregate_type, aggregate_id, event_type, payload)
            VALUES ('appointment', ?, ?, ?::jsonb)
            ON CONFLICT DO NOTHING
            """,
            event.getAppointmentUuid(),
            event.getEventType().name(),
            buildPayloadJson(event, null)
        );
    }

    /**
     * Mark an outbox entry as published.
     */
    public void markPublished(String appointmentUuid) {
        jdbc.update("""
            UPDATE outbox_events SET published_at = now()
            WHERE aggregate_id = ? AND published_at IS NULL
            """,
            appointmentUuid
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String buildPayloadJson(AppointmentEvent event, NotificationResult result) {
        // Simple JSON construction without pulling in a full serializer dependency here
        return String.format(
            "{\"appointmentUuid\":\"%s\",\"patientUuid\":\"%s\",\"eventType\":\"%s\"" +
            ",\"patientPhone\":\"%s\",\"patientEmail\":\"%s\",\"providerMsgId\":\"%s\"}",
            nvl(event.getAppointmentUuid()),
            nvl(event.getPatientUuid()),
            event.getEventType(),
            nvl(event.getPatientPhone()),
            nvl(event.getPatientEmail()),
            result != null && result.getProviderMessageId() != null ? result.getProviderMessageId() : ""
        );
    }

    private String nvl(String s) { return s != null ? s : ""; }
}

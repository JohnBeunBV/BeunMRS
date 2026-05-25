package com.openmrs.notification.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.util.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes every notification attempt to the notification_log table.
 * Also manages the outbox_events table used by the relay loop for
 * guaranteed at-least-once delivery even through provider outages.
 *
 * <p>
 * The JSONB payload stored in notification_log includes all non-PII fields
 * needed to reconstruct an AppointmentEvent for retry (appointmentTime,
 * locationName, comments, timezone, patientName). Phone and e-mail are always
 * masked before storage (NFR-5).
 * </p>
 *
 * <p>
 * FM-7 fix: recordResult() retries up to MAX_DB_RETRIES times with a short
 * backoff if the database is temporarily unavailable during the write.
 * </p>
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    // FM-7: retry parameters for DB write failures
    private static final int MAX_DB_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Record the outcome of a send attempt.
     *
     * FM-7: retries up to MAX_DB_RETRIES times on DataAccessException before
     * giving up. This handles transient DB unavailability (e.g. brief network
     * partition or connection pool exhaustion) without losing the result.
     *
     * FM-1: the unique partial index idx_notification_log_no_duplicate_sent on
     * (tenant_id, patient_uuid, event_type, channel) WHERE status = 'sent'
     * prevents duplicate sent entries at the database level.
     */
    public void recordResult(AppointmentEvent event, String providerName, NotificationResult result) {
        String payloadJson = buildPayloadJson(event, result);
        int attempt = 0;

        while (attempt < MAX_DB_RETRIES) {
            try {
                jdbc.update("""
                        INSERT INTO notification_log
                            (tenant_id, patient_uuid, channel, event_type, status, sent_at, error_message, payload)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
<<<<<<< Updated upstream
                        ON CONFLICT (tenant_id, patient_uuid, event_type, channel)
                            WHERE status = 'sent' DO NOTHING
=======
                        ON CONFLICT (tenant_id, patient_uuid, event_type, channel) WHERE status = 'sent' DO NOTHING
>>>>>>> Stashed changes
                        """,
                        event.getTenantId(),
                        event.getPatientUuid(),
                        providerName,
                        event.getEventType().name(),
                        result.isSuccess() ? "sent" : "failed",
                        result.isSuccess() ? Timestamp.from(Instant.now()) : null,
                        result.getErrorMessage(),
                        payloadJson);
                return; // success
            } catch (DataAccessException ex) {
                attempt++;
                if (attempt >= MAX_DB_RETRIES) {
                    log.error("[OutboxService] DB write failed after {} attempts for appointment={} — result lost",
                            MAX_DB_RETRIES, event.getAppointmentUuid(), ex);
                } else {
                    log.warn("[OutboxService] DB write attempt {}/{} failed for appointment={}, retrying in {}ms",
                            attempt, MAX_DB_RETRIES, event.getAppointmentUuid(), RETRY_BACKOFF_MS);
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /**
     * Write a pending outbox entry — called before the send attempt so
     * we can replay it if the process crashes mid-flight.
     */
    public void writePending(AppointmentEvent event) {
        jdbc.update("""
                INSERT INTO outbox_events
                    (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'appointment', ?, ?, ?::jsonb)
                ON CONFLICT DO NOTHING
                """,
                event.getTenantId(),
                event.getAppointmentUuid(),
                event.getEventType().name(),
                buildPayloadJson(event, null));
    }

    /**
     * Mark an outbox entry as published (scoped to tenant to avoid cross-tenant
     * matches).
     */
    public void markPublished(String appointmentUuid, UUID tenantId) {
        jdbc.update("""
                UPDATE outbox_events SET published_at = now()
                WHERE aggregate_id = ? AND tenant_id = ? AND published_at IS NULL
                """,
                appointmentUuid, tenantId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Builds the JSONB payload stored in notification_log and outbox_events.
     *
     * <p>
     * NFR-5: phone and e-mail are always masked before storage
     * (e.g. "+316****678", "b****@example.com"). Real contact details are only
     * held in memory during the send attempt.
     * </p>
     *
     * <p>
     * Non-PII fields (appointmentTime, locationName, comments, timezone,
     * patientName) are stored unmasked so that {@code FailedNotificationRetryJob}
     * can reconstruct the event without an extra OpenMRS call for those fields.
     * Phone/e-mail are re-fetched from OpenMRS at retry time.
     * </p>
     */
    private String buildPayloadJson(AppointmentEvent event, NotificationResult result) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("appointmentUuid", nvl(event.getAppointmentUuid()));
            node.put("patientUuid", nvl(event.getPatientUuid()));
            node.put("patientName", nvl(event.getPatientName()));
            node.put("eventType", event.getEventType() != null ? event.getEventType().name() : "");
            // NFR-5: masked before storage
            node.put("patientPhone", MessageHelper.mask(event.getPatientPhone()));
            // Non-PII fields — needed by retry job to reconstruct the event
            if (event.getAppointmentTime() != null) {
                node.put("appointmentTime", event.getAppointmentTime().toString());
            }
            node.put("locationName", nvl(event.getLocationName()));
            node.put("comments", nvl(event.getComments()));
            node.put("timezone", nvl(event.getTimezone()));
            node.put("providerMsgId",
                    result != null && result.getProviderMessageId() != null
                            ? result.getProviderMessageId()
                            : "");
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            log.error("Failed to build payload JSON for appointment={}", event.getAppointmentUuid(), ex);
            return "{}";
        }
    }

    private String nvl(String s) {
        return s != null ? s : "";
    }
}

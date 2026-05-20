package com.openmrs.notification.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmrs.notification.model.AppointmentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Manages the scheduled_notifications table.
 *
 * Called by AppointmentEventConsumer whenever an appointment is created,
 * updated or cancelled. Stores reminder rows so that ReminderDispatchJob
 * can fire them at the right moment without needing a second OpenMRS call.
 *
 * Two reminders per appointment:
 *   - type = '24h'  →  send_at = appointmentTime - 24 hours
 *   - type = '1h'   →  send_at = appointmentTime - 1  hour
 *
 * The full AppointmentEvent is serialised to JSONB so the dispatch job
 * has all the data it needs (patient UUID, contact info, location, …).
 */
@Service
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReminderScheduler(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Schedule a 24h and a 1h reminder for this appointment.
     * Called on SCHEDULED events; also after cancelling old reminders on UPDATED.
     *
     * If appointmentTime is null (contact data not yet enriched) we skip
     * reminder scheduling and log a warning. The immediate dispatch still
     * works — only future reminders are skipped.
     */
    public void scheduleReminders(AppointmentEvent event) {
        if (event.getAppointmentTime() == null) {
            log.warn("[Reminder] appointmentTime is null — cannot schedule reminders for appointment={}",
                    event.getAppointmentUuid());
            return;
        }

        String payload = toJson(event);
        Instant base   = event.getAppointmentTime();

        insertReminder(event.getAppointmentUuid(), "24h",
                       base.minus(24, ChronoUnit.HOURS), payload);
        insertReminder(event.getAppointmentUuid(), "1h",
                       base.minus(1,  ChronoUnit.HOURS), payload);

        log.info("[Reminder] Scheduled 24h + 1h reminders for appointment={} (at={})",
                event.getAppointmentUuid(), base);
    }

    /**
     * Mark all pending reminders for this appointment as cancelled.
     * Called on CANCELLED events, and before re-scheduling on UPDATED.
     */
    public void cancelReminders(String appointmentUuid) {
        int rows = jdbc.update("""
            UPDATE scheduled_notifications
               SET status = 'cancelled'
             WHERE appointment_uuid = ?
               AND status = 'pending'
            """, appointmentUuid);

        if (rows > 0) {
            log.info("[Reminder] Cancelled {} pending reminder(s) for appointment={}",
                    rows, appointmentUuid);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Insert one reminder row.
     * ON CONFLICT DO NOTHING prevents duplicate pending rows when a duplicate
     * RabbitMQ message arrives (at-least-once delivery).
     * The partial unique index (appointment_uuid, type) WHERE status='pending'
     * ensures only one active reminder of each type exists at a time.
     */
    private void insertReminder(String appointmentUuid,
                                 String type,
                                 Instant sendAt,
                                 String  payload) {
        jdbc.update("""
            INSERT INTO scheduled_notifications
                    (appointment_uuid, type, send_at, payload)
             VALUES (?, ?, ?, ?::jsonb)
             ON CONFLICT DO NOTHING
            """,
            appointmentUuid,
            type,
            Timestamp.from(sendAt),
            payload
        );
    }

    private String toJson(AppointmentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise AppointmentEvent to JSON", e);
        }
    }
}

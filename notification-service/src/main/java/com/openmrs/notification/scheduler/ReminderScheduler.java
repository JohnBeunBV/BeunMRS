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
import java.util.UUID;

/**
 * Manages the scheduled_notifications table.
 *
 * Two reminders per appointment:
 *   - type = '24h'  →  send_at = appointmentTime - 24 hours
 *   - type = '1h'   →  send_at = appointmentTime - 1  hour
 *
 * The full AppointmentEvent is serialised to JSONB so the dispatch job
 * has all the data it needs without a second OpenMRS call.
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

    public void scheduleReminders(AppointmentEvent event) {
        if (event.getAppointmentTime() == null) {
            log.warn("[Reminder] appointmentTime is null — cannot schedule reminders for appointment={}",
                    event.getAppointmentUuid());
            return;
        }

        String payload = toJson(event);
        Instant base   = event.getAppointmentTime();
        UUID tenantId  = event.getTenantId();

        insertReminder(event.getAppointmentUuid(), tenantId, "24h",
                       base.minus(24, ChronoUnit.HOURS), payload);
        insertReminder(event.getAppointmentUuid(), tenantId, "1h",
                       base.minus(1,  ChronoUnit.HOURS), payload);

        log.info("[Reminder] Scheduled 24h + 1h reminders for appointment={} (at={})",
                event.getAppointmentUuid(), base);
    }

    public void cancelReminders(String appointmentUuid, UUID tenantId) {
        int rows = jdbc.update("""
            UPDATE scheduled_notifications
               SET status = 'cancelled'
             WHERE appointment_uuid = ?
               AND tenant_id = ?
               AND status = 'pending'
            """, appointmentUuid, tenantId);

        if (rows > 0) {
            log.info("[Reminder] Cancelled {} pending reminder(s) for appointment={}",
                    rows, appointmentUuid);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void insertReminder(String appointmentUuid,
                                 UUID   tenantId,
                                 String type,
                                 Instant sendAt,
                                 String  payload) {
        jdbc.update("""
            INSERT INTO scheduled_notifications
                    (appointment_uuid, tenant_id, type, send_at, payload)
             VALUES (?, ?, ?, ?, ?::jsonb)
             ON CONFLICT DO NOTHING
            """,
            appointmentUuid,
            tenantId,
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

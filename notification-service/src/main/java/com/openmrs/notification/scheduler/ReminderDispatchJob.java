package com.openmrs.notification.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.service.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Polls scheduled_notifications every minute and sends reminders that are due.
 *
 * How it works
 * ────────────
 * 1. SELECT rows WHERE send_at <= now() AND status = 'pending'  (batch of 20)
 * 2. For each row:
 *    a. Deserialise the stored AppointmentEvent payload
 *    b. Override eventType → REMINDER_24H or REMINDER_1H
 *       (providers use this to build reminder-specific message text)
 *    c. Call NotificationDispatcher.dispatch() — fans out to all providers
 *    d. On success: UPDATE status = 'sent'
 *    e. On failure: leave status = 'pending' so it retries next minute
 *
 * Why status stays 'pending' on failure
 * ───────────────────────────────────────
 * The reminder will be retried on the next poll. This is acceptable
 * because @Scheduled(fixedDelay) guarantees only one concurrent execution,
 * so there is no risk of parallel double-dispatch on the same row.
 */
@Component
public class ReminderDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderDispatchJob.class);
    private static final int BATCH_SIZE = 20;

    private final JdbcTemplate           jdbc;
    private final NotificationDispatcher dispatcher;
    private final ObjectMapper           objectMapper;

    public ReminderDispatchJob(JdbcTemplate jdbc,
                               NotificationDispatcher dispatcher,
                               ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.dispatcher   = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs every minute (configurable via reminder.dispatch.interval-ms).
     * fixedDelay means the next run starts only after the current one finishes,
     * so there is never parallel execution within a single JVM instance.
     */
    @Scheduled(fixedDelayString   = "${reminder.dispatch.interval-ms:60000}",
               initialDelayString = "${reminder.dispatch.initial-delay-ms:30000}")
    public void dispatch() {

        // ── Stap 1: Haal vervallen reminders op ───────────────────────────────
        List<Map<String, Object>> due = jdbc.queryForList("""
            SELECT id, appointment_uuid, type, payload::text AS payload
              FROM scheduled_notifications
             WHERE send_at <= now()
               AND status  = 'pending'
             ORDER BY send_at
             LIMIT ?
            """, BATCH_SIZE);

        if (due.isEmpty()) {
            log.debug("[Reminder] Geen vervallen reminders");
            return;
        }

        log.info("[Reminder] {} vervallen reminder(s) worden verstuurd", due.size());

        int sent   = 0;
        int errors = 0;

        for (Map<String, Object> row : due) {
            boolean ok = processReminder(row);
            if (ok) sent++; else errors++;
        }

        log.info("[Reminder] Dispatch klaar — verstuurd: {}, fouten: {}", sent, errors);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Process a single reminder row. Returns true if dispatched successfully.
     */
    private boolean processReminder(Map<String, Object> row) {
        String id              = row.get("id").toString();
        String type            = (String) row.get("type");           // '24h' or '1h'
        String payloadJson     = (String) row.get("payload");
        String appointmentUuid = (String) row.get("appointment_uuid");

        try {
            // ── Stap 2a: Deserialise het originele event ────────────────────
            AppointmentEvent event = objectMapper.readValue(payloadJson, AppointmentEvent.class);

            // ── Stap 2b: Overschrijf het event type ─────────────────────────
            // Providers kijken naar dit type om de berichttekst te bepalen.
            AppointmentEvent.EventType reminderType = "24h".equals(type)
                    ? AppointmentEvent.EventType.REMINDER_24H
                    : AppointmentEvent.EventType.REMINDER_1H;
            event.setEventType(reminderType);

            // ── Stap 2c: Dispatch naar alle providers ───────────────────────
            dispatcher.dispatch(event);

            // ── Stap 2d: Markeer als verstuurd ─────────────────────────────
            jdbc.update("""
                UPDATE scheduled_notifications
                   SET status = 'sent', sent_at = now()
                 WHERE id = ?::uuid
                """, id);

            log.info("[Reminder] {} reminder verstuurd — appointment={}",
                    type, appointmentUuid);
            return true;

        } catch (Exception ex) {
            log.error("[Reminder] Fout bij versturen {} reminder voor appointment={}: {}",
                    type, appointmentUuid, ex.getMessage(), ex);
            // Status blijft 'pending' — wordt vanzelf opnieuw geprobeerd
            return false;
        }
    }
}

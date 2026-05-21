package com.openmrs.notification.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.service.NotificationDispatcher;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantContext;
import com.openmrs.notification.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Polls scheduled_notifications every minute and sends reminders that are due.
 *
 * TenantContext is set per reminder row so NotificationDispatcher can resolve
 * the correct provider and credentials for that tenant.
 */
@Component
public class ReminderDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderDispatchJob.class);
    private static final int BATCH_SIZE = 20;

    private final JdbcTemplate           jdbc;
    private final NotificationDispatcher dispatcher;
    private final ObjectMapper           objectMapper;
    private final TenantService          tenantService;

    public ReminderDispatchJob(JdbcTemplate jdbc,
                               NotificationDispatcher dispatcher,
                               ObjectMapper objectMapper,
                               TenantService tenantService) {
        this.jdbc          = jdbc;
        this.dispatcher    = dispatcher;
        this.objectMapper  = objectMapper;
        this.tenantService = tenantService;
    }

    @Scheduled(fixedDelayString   = "${reminder.dispatch.interval-ms:60000}",
               initialDelayString = "${reminder.dispatch.initial-delay-ms:30000}")
    public void dispatch() {

        List<Map<String, Object>> due = jdbc.queryForList("""
            SELECT id, tenant_id, appointment_uuid, type, payload::text AS payload
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

    private boolean processReminder(Map<String, Object> row) {
        String id              = row.get("id").toString();
        UUID   tenantId        = toUuid(row.get("tenant_id"));
        String type            = (String) row.get("type");
        String payloadJson     = (String) row.get("payload");
        String appointmentUuid = (String) row.get("appointment_uuid");

        Tenant tenant = tenantService.findById(tenantId).orElse(null);
        if (tenant == null) {
            log.error("[Reminder] Tenant niet gevonden id={} voor appointment={} — skipping",
                    tenantId, appointmentUuid);
            return false;
        }

        TenantContext.set(tenant);
        try {
            AppointmentEvent event = objectMapper.readValue(payloadJson, AppointmentEvent.class);

            AppointmentEvent.EventType reminderType = "24h".equals(type)
                    ? AppointmentEvent.EventType.REMINDER_24H
                    : AppointmentEvent.EventType.REMINDER_1H;
            event.setEventType(reminderType);
            event.setTenantId(tenantId);

            if (event.getAppointmentTime() != null
                    && event.getAppointmentTime().isBefore(Instant.now())) {
                log.info("[Reminder] Afspraak al voorbij — reminder overgeslagen. type={} appointment={}",
                        type, appointmentUuid);
                jdbc.update("""
                    UPDATE scheduled_notifications
                       SET status = 'skipped', sent_at = now()
                     WHERE id = ?::uuid
                    """, id);
                return true;
            }

            dispatcher.dispatch(event);

            jdbc.update("""
                UPDATE scheduled_notifications
                   SET status = 'sent', sent_at = now()
                 WHERE id = ?::uuid
                """, id);

            log.info("[Reminder] {} reminder verstuurd — appointment={}", type, appointmentUuid);
            return true;

        } catch (Exception ex) {
            log.error("[Reminder] Fout bij versturen {} reminder voor appointment={}: {}",
                    type, appointmentUuid, ex.getMessage(), ex);
            return false;
        } finally {
            TenantContext.clear();
        }
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID)   return (UUID) value;
        if (value instanceof String) return UUID.fromString((String) value);
        throw new IllegalArgumentException("Onverwacht UUID type: " + value.getClass());
    }
}

package com.openmrs.notification.consumer;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.scheduler.ReminderScheduler;
import com.openmrs.notification.service.NotificationDispatcher;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantContext;
import com.openmrs.notification.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes appointment events from RabbitMQ and coordinates:
 *  1. Immediate notification dispatch (bevestiging / wijziging / annulering)
 *  2. Reminder scheduling (24h + 1h before the appointment)
 *
 * TenantContext is set per message from event.getTenantId() — messages without
 * a known tenantId are discarded with a warning.
 */
@Component
public class AppointmentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AppointmentEventConsumer.class);

    private final NotificationDispatcher dispatcher;
    private final ReminderScheduler      reminderScheduler;
    private final TenantService          tenantService;

    public AppointmentEventConsumer(NotificationDispatcher dispatcher,
                                    ReminderScheduler reminderScheduler,
                                    TenantService tenantService) {
        this.dispatcher        = dispatcher;
        this.reminderScheduler = reminderScheduler;
        this.tenantService     = tenantService;
    }

    @RabbitListener(queues = "${rabbitmq.queue.appointments:appointments}")
    public void onAppointment(AppointmentEvent event) {
        log.info("Received appointment event type={} uuid={}",
                event.getEventType(), event.getAppointmentUuid());

        if (!setTenantContext(event)) return;
        try {
            dispatcher.dispatch(event);

            switch (event.getEventType()) {
                case SCHEDULED -> reminderScheduler.scheduleReminders(event);
                case UPDATED -> {
                    reminderScheduler.cancelReminders(event.getAppointmentUuid(), event.getTenantId());
                    reminderScheduler.scheduleReminders(event);
                }
                default -> log.debug("No reminder action for eventType={}", event.getEventType());
            }
        } finally {
            TenantContext.clear();
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.cancelled:appointment.cancelled}")
    public void onCancellation(AppointmentEvent event) {
        log.info("Received cancellation event uuid={}", event.getAppointmentUuid());

        if (!setTenantContext(event)) return;
        try {
            dispatcher.dispatch(event);
            reminderScheduler.cancelReminders(event.getAppointmentUuid(), event.getTenantId());
        } finally {
            TenantContext.clear();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Resolves the tenant from the event and sets TenantContext. Returns false if not found. */
    private boolean setTenantContext(AppointmentEvent event) {
        UUID tenantId = event.getTenantId();
        if (tenantId == null) {
            log.warn("No tenantId in event — skipping uuid={}", event.getAppointmentUuid());
            return false;
        }
        Tenant tenant = tenantService.findById(tenantId).orElse(null);
        if (tenant == null) {
            log.warn("Tenant not found id={} — skipping appointment={}", tenantId, event.getAppointmentUuid());
            return false;
        }
        TenantContext.set(tenant);
        return true;
    }
}

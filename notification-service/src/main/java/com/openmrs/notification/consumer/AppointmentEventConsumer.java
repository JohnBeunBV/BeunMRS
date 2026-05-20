package com.openmrs.notification.consumer;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.scheduler.ReminderScheduler;
import com.openmrs.notification.service.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes appointment events from RabbitMQ and coordinates:
 *  1. Immediate notification dispatch (bevestiging / wijziging / annulering)
 *  2. Reminder scheduling (24h + 1h before the appointment)
 *
 * Two listeners:
 *  - appointments queue      → SCHEDULED and UPDATED events
 *  - appointment.cancelled   → CANCELLED events
 *
 * Reminder logic per event type
 * ───────────────────────────────────────────────────────────────────────────
 * SCHEDULED : dispatch confirmation + schedule 2 reminders (24h, 1h)
 * UPDATED   : dispatch update notice + cancel old reminders + reschedule
 *             (appointment time may have changed)
 * CANCELLED : dispatch cancellation notice + cancel pending reminders
 *
 * Spring AMQP handles JSON deserialisation (Jackson), acknowledgement, and
 * requeue-on-failure automatically. Messages that exhaust retries are
 * dead-lettered to the DLX queues defined in topology.json.
 */
@Component
public class AppointmentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AppointmentEventConsumer.class);

    private final NotificationDispatcher dispatcher;
    private final ReminderScheduler      reminderScheduler;

    public AppointmentEventConsumer(NotificationDispatcher dispatcher,
                                    ReminderScheduler reminderScheduler) {
        this.dispatcher        = dispatcher;
        this.reminderScheduler = reminderScheduler;
    }

    /**
     * Handles SCHEDULED and UPDATED appointment events.
     */
    @RabbitListener(queues = "${rabbitmq.queue.appointments:appointments}")
    public void onAppointment(AppointmentEvent event) {
        log.info("Received appointment event type={} uuid={}",
                event.getEventType(), event.getAppointmentUuid());

        // Always send an immediate notification (confirmation or update)
        dispatcher.dispatch(event);

        switch (event.getEventType()) {
            case SCHEDULED -> {
                // Schedule a 24h and a 1h reminder
                reminderScheduler.scheduleReminders(event);
            }
            case UPDATED -> {
                // Appointment time may have changed — cancel old reminders and
                // schedule new ones based on the updated appointmentTime
                reminderScheduler.cancelReminders(event.getAppointmentUuid());
                reminderScheduler.scheduleReminders(event);
            }
            default -> log.debug("No reminder action for eventType={}", event.getEventType());
        }
    }

    /**
     * Handles CANCELLED appointment events.
     */
    @RabbitListener(queues = "${rabbitmq.queue.cancelled:appointment.cancelled}")
    public void onCancellation(AppointmentEvent event) {
        log.info("Received cancellation event uuid={}", event.getAppointmentUuid());

        // Send the cancellation notification immediately
        dispatcher.dispatch(event);

        // Cancel any pending reminders — patient no longer needs them
        reminderScheduler.cancelReminders(event.getAppointmentUuid());
    }
}

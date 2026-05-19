package com.openmrs.notification.consumer;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.service.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes appointment events from RabbitMQ.
 *
 * Two listeners:
 *  - appointments queue   → SCHEDULED and UPDATED events
 *  - appointment.cancelled → CANCELLED events
 *
 * Spring AMQP handles deserialization (Jackson), acknowledgement,
 * and requeue-on-failure automatically. Messages that exhaust retries
 * are dead-lettered to the DLX queues defined in topology.json.
 */
@Component
public class AppointmentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AppointmentEventConsumer.class);

    private final NotificationDispatcher dispatcher;

    public AppointmentEventConsumer(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @RabbitListener(queues = "${rabbitmq.queue.appointments:appointments}")
    public void onAppointment(AppointmentEvent event) {
        log.info("Received appointment event type={} uuid={}",
                event.getEventType(), event.getAppointmentUuid());
        dispatcher.dispatch(event);
    }

    @RabbitListener(queues = "${rabbitmq.queue.cancelled:appointment.cancelled}")
    public void onCancellation(AppointmentEvent event) {
        log.info("Received cancellation event uuid={}", event.getAppointmentUuid());
        dispatcher.dispatch(event);
    }
}

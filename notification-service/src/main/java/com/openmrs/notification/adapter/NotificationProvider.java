package com.openmrs.notification.adapter;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationChannel;
import com.openmrs.notification.model.NotificationResult;

/**
 * Provider adapter contract.
 *
 * Adding a new provider in sprint 3 means:
 *   1. Create a class implementing this interface.
 *   2. Annotate it @Component (or @ConditionalOnProperty for feature-flagging).
 *   3. Done — no changes to NotificationDispatcher or any other class.
 *
 * Adapters are decoupled from the event bus and from OpenMRS.
 * They receive only the minimal canonical AppointmentEvent they need.
 */
public interface NotificationProvider {

    /**
     * The channel this adapter handles (SMS, EMAIL, PUSH).
     * Used by the dispatcher to route to the correct adapter(s).
     */
    NotificationChannel channel();

    /**
     * Human-readable name used in logging and the outbox.
     */
    String providerName();

    /**
     * Send a notification for the given event.
     *
     * @param event  the appointment event to notify about
     * @return result indicating success/failure and provider reference id
     */
    NotificationResult send(AppointmentEvent event);

    /**
     * Whether this provider is currently enabled and healthy.
     * Dispatcher skips disabled providers gracefully.
     */
    default boolean isEnabled() {
        return true;
    }

}

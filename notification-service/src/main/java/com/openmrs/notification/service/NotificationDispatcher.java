package com.openmrs.notification.service;

import com.openmrs.notification.adapter.NotificationProvider;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.outbox.OutboxService;
import com.openmrs.notification.util.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Receives a canonical AppointmentEvent and fans it out to every
 * enabled NotificationProvider.
 *
 * This class never knows which providers are registered — Spring
 * injects all NotificationProvider beans automatically. Adding a new
 * provider in sprint 3 requires zero changes here.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final List<NotificationProvider> providers;
    private final OutboxService outboxService;

    public NotificationDispatcher(List<NotificationProvider> providers, OutboxService outboxService) {
        this.providers = providers;
        this.outboxService = outboxService;
    }

    /**
     * Dispatch an event to all enabled providers and record results in the outbox.
     */
    public void dispatch(AppointmentEvent event) {
        log.info("Dispatching event type={} appointment={} phone={} email={} to {} provider(s)",
                event.getEventType(), event.getAppointmentUuid(),
                MessageHelper.mask(event.getPatientPhone()),
                MessageHelper.mask(event.getPatientEmail()),
                providers.size());

        for (NotificationProvider provider : providers) {
            if (!provider.isEnabled()) {
                log.debug("Skipping disabled provider={}", provider.providerName());
                continue;
            }
            try {
                NotificationResult result = provider.send(event);
                outboxService.recordResult(event, provider.providerName(), result);
            } catch (Exception ex) {
                log.error("Unhandled error in provider={} for appointment={}",
                        provider.providerName(), event.getAppointmentUuid(), ex);
                outboxService.recordResult(event, provider.providerName(),
                        NotificationResult.failure("Unhandled exception: " + ex.getMessage()));
            }
        }
    }
}
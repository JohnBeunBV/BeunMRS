package com.openmrs.notification.adapter;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationChannel;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;

/**
 * Provider adapter contract.
 *
 * Credentials are passed at runtime from the tenant record so that each
 * tenant can use the same provider type with their own API keys.
 * Adding a new provider = one new class, zero other changes.
 */
public interface NotificationProvider {

    NotificationChannel channel();
    String providerName();

    /**
     * Send a notification using the tenant's credentials.
     *
     * @param event       the appointment event to notify about
     * @param credentials decrypted tenant credentials for this provider
     */
    NotificationResult send(AppointmentEvent event, ProviderCredentials credentials);

    default boolean isEnabled() { return true; }
}

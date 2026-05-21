package com.openmrs.notification.service;

import com.openmrs.notification.adapter.NotificationProvider;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;
import com.openmrs.notification.outbox.OutboxService;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantContext;
import com.openmrs.notification.tenant.TenantService;
import com.openmrs.notification.util.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Routes each AppointmentEvent to the single provider configured for the
 * current tenant. Falls back to SwiftSend if the configured provider is
 * disabled or not found.
 *
 * TenantContext must be set before calling dispatch().
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final String FALLBACK_PROVIDER = "SwiftSend";

    private final List<NotificationProvider> providers;
    private final OutboxService              outboxService;
    private final TenantService              tenantService;

    public NotificationDispatcher(List<NotificationProvider> providers,
                                  OutboxService outboxService,
                                  TenantService tenantService) {
        this.providers     = providers;
        this.outboxService = outboxService;
        this.tenantService = tenantService;
    }

    public void dispatch(AppointmentEvent event) {
        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            log.error("No tenant in TenantContext for appointment={}", event.getAppointmentUuid());
            return;
        }

        // Propagate tenantId into the event so outbox/log can reference it
        event.setTenantId(tenant.getId());

        String targetName = tenant.getProviderName();
        ProviderCredentials credentials = new ProviderCredentials(
                tenantService.decryptProviderApiKey(tenant),
                tenant.getProviderExtraEnc() != null ? tenantService.decryptProviderExtra(tenant) : null
        );

        NotificationProvider target = providers.stream()
                .filter(p -> p.isEnabled() && p.providerName().equalsIgnoreCase(targetName))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Provider '{}' not found or disabled — falling back to {}", targetName, FALLBACK_PROVIDER);
                    return providers.stream()
                            .filter(p -> p.isEnabled() && p.providerName().equalsIgnoreCase(FALLBACK_PROVIDER))
                            .findFirst()
                            .orElse(null);
                });

        if (target == null) {
            log.error("No enabled provider found for tenant={} appointment={}",
                    tenant.getSlug(), event.getAppointmentUuid());
            return;
        }

        log.info("Dispatching event type={} appointment={} phone={} email={} tenant={} provider={}",
                event.getEventType(), event.getAppointmentUuid(),
                MessageHelper.mask(event.getPatientPhone()),
                MessageHelper.mask(event.getPatientEmail()),
                tenant.getSlug(), target.providerName());

        try {
            NotificationResult result = target.send(event, credentials);
            outboxService.recordResult(event, target.providerName(), result);
        } catch (Exception ex) {
            log.error("Unhandled error in provider={} for appointment={}",
                    target.providerName(), event.getAppointmentUuid(), ex);
            outboxService.recordResult(event, target.providerName(),
                    NotificationResult.failure("Unhandled exception: " + ex.getMessage()));
        }
    }
}

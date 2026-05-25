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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry              meterRegistry;

    public NotificationDispatcher(List<NotificationProvider> providers,
                                  OutboxService outboxService,
                                  TenantService tenantService,
                                  MeterRegistry meterRegistry) {
        this.providers     = providers;
        this.outboxService = outboxService;
        this.tenantService = tenantService;
        this.meterRegistry = meterRegistry;
    }

    public void dispatch(AppointmentEvent event) {
        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            log.error("No tenant in TenantContext for appointment={}", event.getAppointmentUuid());
            return;
        }

        // Propagate tenantId and timezone into the event (NFR-13: per-tenant timezone)
        event.setTenantId(tenant.getId());
        event.setTimezone(tenant.getTimezone());

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

        log.info("Dispatching event type={} appointment={} phone={} tenant={} provider={}",
                event.getEventType(), event.getAppointmentUuid(),
                MessageHelper.mask(event.getPatientPhone()),
                tenant.getSlug(), target.providerName());

        String providerName = target.providerName();
        String tenantSlug   = tenant.getSlug();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            NotificationResult result = target.send(event, credentials);
            sample.stop(meterRegistry.timer("provider_call_duration_seconds",
                    "provider", providerName));
            if (result.isSuccess()) {
                meterRegistry.counter("notifications_sent_total",
                        "provider", providerName, "tenant", tenantSlug).increment();
            } else {
                meterRegistry.counter("notifications_failed_total",
                        "provider", providerName, "tenant", tenantSlug).increment();
            }
            outboxService.recordResult(event, providerName, result);
        } catch (Exception ex) {
            sample.stop(meterRegistry.timer("provider_call_duration_seconds",
                    "provider", providerName));
            meterRegistry.counter("notifications_failed_total",
                    "provider", providerName, "tenant", tenantSlug).increment();
            log.error("Unhandled error in provider={} for appointment={}",
                    providerName, event.getAppointmentUuid(), ex);
            outboxService.recordResult(event, providerName,
                    NotificationResult.failure("Unhandled exception: " + ex.getMessage()));
        }
    }
}

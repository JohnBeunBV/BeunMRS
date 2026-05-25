package com.openmrs.notification.service;

import com.openmrs.notification.adapter.NotificationProvider;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;
import com.openmrs.notification.outbox.OutboxService;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantContext;
import com.openmrs.notification.tenant.TenantService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 6a — NotificationDispatcher: routes to exactly one provider per tenant.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationDispatcherTest {

    @Mock private NotificationProvider swiftSend;
    @Mock private NotificationProvider securePost;
    @Mock private OutboxService        outboxService;
    @Mock private TenantService        tenantService;

    private NotificationDispatcher dispatcher;
    private Tenant                  tenant;

    @BeforeEach
    void setUp() {
        when(swiftSend.providerName()).thenReturn("SwiftSend");
        when(swiftSend.isEnabled()).thenReturn(true);
        when(securePost.providerName()).thenReturn("SecurePost");
        when(securePost.isEnabled()).thenReturn(true);

        dispatcher = new NotificationDispatcher(
                List.of(swiftSend, securePost), outboxService, tenantService,
                new SimpleMeterRegistry());

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setSlug("test-tenant");
        tenant.setProviderName("SwiftSend");
        tenant.setTimezone("Europe/Amsterdam");
        tenant.setProviderApiKeyEnc("enc-key");

        when(tenantService.decryptProviderApiKey(tenant)).thenReturn("api-key-123");
        when(swiftSend.send(any(), any())).thenReturn(NotificationResult.ok("msg-1"));

        TenantContext.set(tenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void dispatch_routesToConfiguredProvider() {
        AppointmentEvent event = scheduledEvent();

        dispatcher.dispatch(event);

        verify(swiftSend).send(eq(event), any(ProviderCredentials.class));
        verify(securePost, never()).send(any(), any());
    }

    @Test
    void dispatch_recordsResultInOutbox() {
        AppointmentEvent event = scheduledEvent();

        dispatcher.dispatch(event);

        verify(outboxService).recordResult(eq(event), eq("SwiftSend"), any(NotificationResult.class));
    }

    @Test
    void dispatch_setsTimezoneOnEvent() {
        AppointmentEvent event = scheduledEvent();
        event.setTimezone(null);

        dispatcher.dispatch(event);

        // Dispatcher should propagate tenant timezone to event
        assert "Europe/Amsterdam".equals(event.getTimezone());
    }

    @Test
    void dispatch_fallsBackToSwiftSendWhenProviderNotFound() {
        tenant.setProviderName("NonExistentProvider");
        when(swiftSend.send(any(), any())).thenReturn(NotificationResult.ok("fallback-msg"));

        dispatcher.dispatch(scheduledEvent());

        verify(swiftSend).send(any(), any());
    }

    @Test
    void dispatch_noTenantInContext_doesNothing() {
        TenantContext.clear();
        dispatcher.dispatch(scheduledEvent());

        verify(swiftSend, never()).send(any(), any());
        verify(outboxService, never()).recordResult(any(), any(), any());
    }

    @Test
    void dispatch_providerThrowsException_recordsFailure() {
        when(swiftSend.send(any(), any())).thenThrow(new RuntimeException("Verbindingsfout"));

        dispatcher.dispatch(scheduledEvent());

        verify(outboxService).recordResult(any(), eq("SwiftSend"),
                argThat(r -> !r.isSuccess()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AppointmentEvent scheduledEvent() {
        AppointmentEvent e = new AppointmentEvent();
        e.setTenantId(tenant.getId());
        e.setAppointmentUuid("appt-uuid-001");
        e.setPatientUuid("patient-uuid-001");
        e.setEventType(AppointmentEvent.EventType.SCHEDULED);
        return e;
    }
}

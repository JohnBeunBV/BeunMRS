package com.openmrs.notification.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmrs.notification.adapter.NotificationProvider;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.service.PersonContactService;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NFR-6e / NFR-7 — FailedNotificationRetryJob: exponentiële backoff (5 → 15 min)
 * en na de derde mislukking status 'permanently_failed'; bij succes status 'sent'.
 *
 * <p>Deze test asserteert de retry-statemachine die voorheen door geen enkele test
 * werd gevalideerd (alleen "klasse aanwezig" in de matrix).</p>
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes", "null"})
class FailedNotificationRetryJobTest {

    @Mock private JdbcTemplate         jdbc;
    @Mock private TenantService        tenantService;
    @Mock private PersonContactService personContactService;
    @Mock private NotificationProvider provider;

    private FailedNotificationRetryJob job;

    private final UUID   tenantId = UUID.randomUUID();
    private final String rowId    = "22222222-2222-2222-2222-222222222222";

    @BeforeEach
    void setUp() {
        job = new FailedNotificationRetryJob(
                jdbc, List.of(provider), tenantService, personContactService,
                new ObjectMapper(), new SimpleMeterRegistry());
    }

    // ── Backoff-stappen ────────────────────────────────────────────────────────

    @Test
    void retry_firstFailure_schedulesBackoffOf5Minutes() {
        stubFailedRow(0);                 // retry_count 0 → poging 1
        stubProvider("SwiftSend", NotificationResult.failure("503"));

        job.retryFailed();

        // newCount=1, backoff = 5 min, blijft 'failed' (geen permanently_failed)
        verify(jdbc).update(contains("next_retry_at"), eq(1), eq(5L), anyString(), eq(rowId));
        verify(jdbc, never()).update(contains("permanently_failed"), any(), any(), any());
    }

    @Test
    void retry_secondFailure_schedulesBackoffOf15Minutes() {
        stubFailedRow(1);                 // retry_count 1 → poging 2
        stubProvider("SwiftSend", NotificationResult.failure("503"));

        job.retryFailed();

        verify(jdbc).update(contains("next_retry_at"), eq(2), eq(15L), anyString(), eq(rowId));
    }

    @Test
    void retry_thirdFailure_marksPermanentlyFailed() {
        stubFailedRow(2);                 // retry_count 2 → poging 3 = laatste
        stubProvider("SwiftSend", NotificationResult.failure("503"));

        job.retryFailed();

        // newCount=3 >= MAX_RETRIES → permanently_failed, geen nieuwe backoff
        verify(jdbc).update(contains("permanently_failed"), eq(3), anyString(), eq(rowId));
        verify(jdbc, never()).update(contains("next_retry_at"), any(), any(), any(), any());
    }

    @Test
    void retry_success_marksSentWithIncrementedRetryCount() {
        stubFailedRow(1);                 // retry_count 1
        stubProvider("SwiftSend", NotificationResult.ok("provider-msg-9"));

        job.retryFailed();

        // status 'sent', retry_count = 1 + 1 = 2
        verify(jdbc).update(contains("'sent'"), eq(2), eq(rowId));
        verify(jdbc, never()).update(contains("permanently_failed"), any(), any(), any());
    }

    @Test
    void retry_providerNoLongerAvailable_marksPermanentlyFailed() {
        stubFailedRow(0);
        // provider heet anders dan de channel in de rij → niet meer beschikbaar
        when(provider.isEnabled()).thenReturn(true);
        when(provider.providerName()).thenReturn("AsyncFlow");
        when(tenantService.findById(any())).thenReturn(Optional.of(tenant()));

        job.retryFailed();

        verify(jdbc).update(contains("permanently_failed"), eq(3), anyString(), eq(rowId));
        verify(provider, never()).send(any(), any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubFailedRow(int retryCount) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", rowId);
        row.put("tenant_id", tenantId);
        row.put("patient_uuid", "patient-retry-001");
        row.put("channel", "SwiftSend");
        row.put("event_type", "SCHEDULED");
        row.put("payload", "{\"appointmentUuid\":\"appt-retry-001\",\"patientUuid\":\"patient-retry-001\"}");
        row.put("retry_count", retryCount);
        when(jdbc.queryForList(anyString(), eq(3), eq(10))).thenReturn((List) List.of(row));
    }

    private void stubProvider(String name, NotificationResult result) {
        when(provider.isEnabled()).thenReturn(true);
        when(provider.providerName()).thenReturn(name);
        when(provider.send(any(), any())).thenReturn(result);
        when(tenantService.findById(any())).thenReturn(Optional.of(tenant()));
        when(tenantService.decryptProviderApiKey(any())).thenReturn("decrypted-key");
    }

    private Tenant tenant() {
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setSlug("test-tenant");
        t.setTimezone("Europe/Amsterdam");
        return t;
    }
}

package com.openmrs.notification.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NFR-6e / NFR-7 (FM-2, FM-9) — OutboxRelayJob: publiceert ongepubliceerde
 * outbox-events naar RabbitMQ, hoogt retry_count op bij een publicatiefout en
 * zet na MAX_RETRIES (5) {@code failed_at}. Bij een onbekende tenant wordt de
 * entry direct als mislukt gemarkeerd zonder publicatie.
 *
 * <p>Dekt de relay-loop die voorheen alleen via de chaos-test (operationeel)
 * werd aangetoond — nu ook met unit-asserts.</p>
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes", "null"})
class OutboxRelayJobTest {

    @Mock private JdbcTemplate   jdbc;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private TenantService  tenantService;

    private OutboxRelayJob job;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID rowId    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        job = new OutboxRelayJob(jdbc, rabbitTemplate, new ObjectMapper(), tenantService);
    }

    @Test
    void relay_pendingEvent_publishesToRabbitAndMarksPublished() {
        stubPendingRow(0);
        when(tenantService.findById(any())).thenReturn(Optional.of(tenant()));

        job.relay();

        verify(rabbitTemplate).convertAndSend(eq("openmrs.events"), anyString(), (Object) any());
        verify(jdbc).update(contains("published_at"), eq(rowId));
    }

    @Test
    void relay_publishFails_belowMax_incrementsRetryCountOnly() {
        stubPendingRow(0);                // newRetryCount = 1 (< 5)
        when(tenantService.findById(any())).thenReturn(Optional.of(tenant()));
        doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any());

        job.relay();

        verify(jdbc).update(eq("UPDATE outbox_events SET retry_count = ? WHERE id = ?"), eq(1), eq(rowId));
        verify(jdbc, never()).update(contains("failed_at"), any(), any());
    }

    @Test
    void relay_publishFails_atMaxRetries_marksFailed() {
        stubPendingRow(4);                // newRetryCount = 5 = MAX_RETRIES
        when(tenantService.findById(any())).thenReturn(Optional.of(tenant()));
        doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any());

        job.relay();

        verify(jdbc).update(contains("failed_at"), eq(5), eq(rowId));
    }

    @Test
    void relay_tenantNotFound_marksFailedWithoutPublishing() {
        stubPendingRow(0);
        when(tenantService.findById(any())).thenReturn(Optional.empty());

        job.relay();

        verify(jdbc).update(contains("failed_at"), eq(rowId));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), (Object) any());
    }

    @Test
    void relay_noPendingEvents_doesNothing() {
        when(jdbc.queryForList(anyString(), eq(20))).thenReturn(List.of());

        job.relay();

        verifyNoInteractions(rabbitTemplate);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubPendingRow(int retryCount) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", rowId);
        row.put("tenant_id", tenantId);
        row.put("aggregate_id", "appt-relay-001");
        row.put("event_type", "SCHEDULED");
        row.put("payload", "{\"patientUuid\":\"p1\"}");
        row.put("retry_count", retryCount);
        when(jdbc.queryForList(anyString(), eq(20))).thenReturn((List) List.of(row));
    }

    private Tenant tenant() {
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setSlug("test-tenant");
        return t;
    }
}

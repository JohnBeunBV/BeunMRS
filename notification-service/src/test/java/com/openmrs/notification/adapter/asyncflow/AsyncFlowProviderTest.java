package com.openmrs.notification.adapter.asyncflow;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 6e — AsyncFlowProvider: command submit + pending-tracking, X-API-KEY aanwezig,
 * status-polling cycle (completed / failed).
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "null"})
class AsyncFlowProviderTest {

    @Mock private RestTemplate  restTemplate;
    @Mock private JdbcTemplate  jdbc;
    @Mock private TenantService tenantService;

    private AsyncFlowProvider  provider;
    private ProviderCredentials credentials;
    private final UUID          tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        provider    = new AsyncFlowProvider(restTemplate, jdbc, tenantService,
                "http://fakecomworld:8080", "group-1");
        credentials = new ProviderCredentials("sk-async-key", null);
    }

    // ── send ──────────────────────────────────────────────────────────────────

    @Test
    void send_returnsSuccessWithPendingPrefix() {
        stubSubmitResponse("cmd-abc");

        NotificationResult result = provider.send(scheduledEvent(), credentials);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProviderMessageId()).startsWith("pending:");
        assertThat(result.getProviderMessageId()).contains("cmd-abc");
    }

    @Test
    void send_includesApiKeyAndGroupHeaders() {
        stubSubmitResponse("cmd-hdr");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        provider.send(scheduledEvent(), credentials);

        verify(restTemplate).exchange(
                contains("/asyncflow"),
                eq(HttpMethod.POST),
                captor.capture(),
                eq(Map.class));

        HttpHeaders headers = captor.getValue().getHeaders();
        assertThat(headers.getFirst("X-API-KEY")).isEqualTo("sk-async-key");
        assertThat(headers.getFirst("X-STUDENT-GROUP")).isEqualTo("group-1");
    }

    @Test
    void send_persistsCommandToDatabase() {
        stubSubmitResponse("cmd-persist");

        provider.send(scheduledEvent(), credentials);

        verify(jdbc).update(contains("async_flow_commands"),
                eq("cmd-persist"), eq(tenantId), eq("appt-af-001"));
    }

    @Test
    void send_httpFailure_returnsFailure() {
        when(restTemplate.exchange(contains("/asyncflow"), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        NotificationResult result = provider.send(scheduledEvent(), credentials);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Connection refused");
    }

    // ── pollPendingCommands ───────────────────────────────────────────────────

    @Test
    void pollPendingCommands_emptyList_makesNoHttpCalls() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        provider.pollPendingCommands();

        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(), eq(Map.class));
    }

    @Test
    void pollPendingCommands_completedStatus_updatesCommandAndLog() {
        stubPendingRow("cmd-done", "appt-done");
        stubStatusResponse("cmd-done", "completed");

        provider.pollPendingCommands();

        // Command updated to 'completed'
        verify(jdbc).update(
                contains("UPDATE async_flow_commands"),
                eq("completed"), eq("cmd-done"));

        // Notification log updated to 'sent'
        verify(jdbc).update(contains("notification_log"), eq("sent"), eq("sent"), contains("appt-done"));
    }

    @Test
    void pollPendingCommands_failedStatus_updatesCommandToFailed() {
        stubPendingRow("cmd-fail", "appt-fail");
        stubStatusResponse("cmd-fail", "failed");

        provider.pollPendingCommands();

        verify(jdbc).update(
                contains("UPDATE async_flow_commands"),
                eq("failed"), eq("cmd-fail"));
    }

    @Test
    void pollPendingCommands_pendingStatus_doesNotUpdateCommand() {
        stubPendingRow("cmd-still-pending", "appt-pending");
        stubStatusResponse("cmd-still-pending", "processing");

        provider.pollPendingCommands();

        // No update to async_flow_commands for non-terminal status
        verify(jdbc, never()).update(contains("UPDATE async_flow_commands"), any(), any());
    }

    @Test
    void pollPendingCommands_tenantNotFound_skipsRow() {
        Map<String, Object> row = pendingRow("cmd-orphan", "appt-orphan");
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row));
        when(tenantService.findById(tenantId)).thenReturn(Optional.empty());

        provider.pollPendingCommands();

        // No status check call when tenant can't be resolved
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(), eq(Map.class));
    }

    // ── providerName ──────────────────────────────────────────────────────────

    @Test
    void providerName_isAsyncFlow() {
        assertThat(provider.providerName()).isEqualTo("AsyncFlow");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubSubmitResponse(String trackingId) {
        ResponseEntity<Map> resp = ResponseEntity.ok(Map.of("trackingId", trackingId));
        when(restTemplate.exchange(contains("/asyncflow"), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(resp);
    }

    private void stubPendingRow(String commandId, String appointmentUuid) {
        Map<String, Object> row = pendingRow(commandId, appointmentUuid);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row));

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        when(tenantService.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantService.decryptProviderApiKey(tenant)).thenReturn("sk-async-key");
    }

    private Map<String, Object> pendingRow(String commandId, String appointmentUuid) {
        Map<String, Object> row = new HashMap<>();
        row.put("command_id",       commandId);
        row.put("appointment_uuid", appointmentUuid);
        row.put("tenant_id",        tenantId);
        return row;
    }

    private void stubStatusResponse(String commandId, String status) {
        ResponseEntity<Map> resp = ResponseEntity.ok(Map.of("status", status));
        when(restTemplate.exchange(
                contains("/asyncflow/" + commandId),
                eq(HttpMethod.GET),
                any(),
                eq(Map.class)))
                .thenReturn(resp);
    }

    private AppointmentEvent scheduledEvent() {
        AppointmentEvent e = new AppointmentEvent();
        e.setTenantId(tenantId);
        e.setAppointmentUuid("appt-af-001");
        e.setPatientUuid("patient-af-001");
        e.setPatientPhone("+31612345678");
        e.setEventType(AppointmentEvent.EventType.SCHEDULED);
        e.setAppointmentTime(Instant.parse("2026-05-22T10:00:00Z"));
        e.setTimezone("Europe/Amsterdam");
        return e;
    }
}

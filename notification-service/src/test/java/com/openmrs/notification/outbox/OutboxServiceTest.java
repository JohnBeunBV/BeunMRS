package com.openmrs.notification.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 6g — OutboxService: INSERT en markPublished zijn scoped op tenant_id; PII wordt gemaskeerd
 * in de JSONB payload (NFR-5).
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"null", "unchecked"})
class OutboxServiceTest {

    @Mock private JdbcTemplate jdbc;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxService(jdbc, new ObjectMapper());
    }

    // ── recordResult ──────────────────────────────────────────────────────────

    @Test
    void recordResult_success_insertsWithSentStatus() {
        UUID tenantId = UUID.randomUUID();
        AppointmentEvent event = event(tenantId);

        outboxService.recordResult(event, "SwiftSend", NotificationResult.ok("msg-001"));

        // Verify that update was called with the correct SQL and that tenant_id + status are present
        verify(jdbc).update(
                contains("notification_log"),
                eq(tenantId),
                eq("patient-uuid"),
                eq("SwiftSend"),
                eq("SCHEDULED"),
                eq("sent"),
                any(),   // Timestamp
                isNull(),// errorMessage
                anyString() // payload JSON
        );
    }

    @Test
    void recordResult_failure_insertsWithFailedStatus() {
        UUID tenantId = UUID.randomUUID();
        AppointmentEvent event = event(tenantId);

        outboxService.recordResult(event, "SwiftSend", NotificationResult.failure("Timeout"));

        verify(jdbc).update(
                contains("notification_log"),
                eq(tenantId),
                any(),
                any(),
                any(),
                eq("failed"),
                isNull(),      // sent_at null on failure
                eq("Timeout"), // errorMessage
                anyString()    // payload JSON
        );
    }

    @Test
    void recordResult_payloadMasksPhone() {
        UUID tenantId = UUID.randomUUID();
        AppointmentEvent event = event(tenantId);
        event.setPatientPhone("+31612345678");

        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(inv -> {
            Object[] args = inv.getArguments();
            captured.set((String) args[args.length - 1]); // last arg = payload JSON
            return 1;
        }).when(jdbc).update(
                contains("notification_log"),
                any(), any(), any(), any(), any(), any(), any(), any()
        );

        outboxService.recordResult(event, "SwiftSend", NotificationResult.ok("x"));

        assertThat(captured.get())
                .as("phone must be masked in DB")
                .contains("+31****678")   // first 3 + **** + last 3
                .doesNotContain("+31612345678");
    }

    @Test
    void recordResult_payloadContainsNonPiiFieldsUnmasked() {
        UUID tenantId = UUID.randomUUID();
        AppointmentEvent event = event(tenantId);
        event.setLocationName("Polikliniek Noord");
        event.setComments("Nuchter komen");

        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(inv -> {
            Object[] args = inv.getArguments();
            captured.set((String) args[args.length - 1]);
            return 1;
        }).when(jdbc).update(
                contains("notification_log"),
                any(), any(), any(), any(), any(), any(), any(), any()
        );

        outboxService.recordResult(event, "SwiftSend", NotificationResult.ok("x"));

        assertThat(captured.get())
                .contains("Polikliniek Noord")
                .contains("Nuchter komen")
                .contains("Europe/Amsterdam");
    }

    @Test
    void recordResult_dbFailsOnce_retriesAndSucceeds() {
        UUID tenantId = UUID.randomUUID();
        AppointmentEvent event = event(tenantId);

        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("DB tijdelijk neer"))
                .thenReturn(1);

        assertThatCode(() -> outboxService.recordResult(event, "SwiftSend", NotificationResult.ok("x")))
                .doesNotThrowAnyException();

        verify(jdbc, times(2)).update(contains("notification_log"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordResult_dbFailsAllRetries_doesNotThrow() {
        UUID tenantId = UUID.randomUUID();
        AppointmentEvent event = event(tenantId);

        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("DB neer"));

        assertThatCode(() -> outboxService.recordResult(event, "SwiftSend", NotificationResult.ok("x")))
                .doesNotThrowAnyException();

        verify(jdbc, times(3)).update(contains("notification_log"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── markPublished ─────────────────────────────────────────────────────────

    @Test
    void markPublished_scopedOnTenantId() {
        UUID tenantId = UUID.randomUUID();

        outboxService.markPublished("appt-001", tenantId);

        verify(jdbc).update(
                contains("UPDATE outbox_events"),
                eq("appt-001"),
                eq(tenantId)
        );
    }

    @Test
    void markPublished_differentTenants_doNotInterfere() {
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();

        outboxService.markPublished("appt-shared", tenant1);
        outboxService.markPublished("appt-shared", tenant2);

        verify(jdbc).update(contains("UPDATE outbox_events"), eq("appt-shared"), eq(tenant1));
        verify(jdbc).update(contains("UPDATE outbox_events"), eq("appt-shared"), eq(tenant2));
    }

    // ── writePending ──────────────────────────────────────────────────────────

    @Test
    void writePending_insertsWithTenantId() {
        UUID tenantId = UUID.randomUUID();
        AppointmentEvent event = event(tenantId);

        outboxService.writePending(event);

        verify(jdbc).update(
                contains("INSERT INTO outbox_events"),
                eq(tenantId),
                eq("appt-outbox-001"),
                eq("SCHEDULED"),
                anyString() // payload JSON
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AppointmentEvent event(UUID tenantId) {
        AppointmentEvent e = new AppointmentEvent();
        e.setTenantId(tenantId);
        e.setAppointmentUuid("appt-outbox-001");
        e.setPatientUuid("patient-uuid");
        e.setEventType(AppointmentEvent.EventType.SCHEDULED);
        e.setTimezone("Europe/Amsterdam");
        e.setAppointmentTime(Instant.parse("2026-05-22T10:00:00Z"));
        return e;
    }
}

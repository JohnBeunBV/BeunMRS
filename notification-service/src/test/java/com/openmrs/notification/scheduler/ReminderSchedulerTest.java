package com.openmrs.notification.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openmrs.notification.model.AppointmentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 6h — ReminderScheduler: send_at berekening klopt (24h / 1h voor afspraak),
 * annulering is scoped op (appointmentUuid, tenantId), null appointmentTime wordt overgeslagen.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class ReminderSchedulerTest {

    @Mock private JdbcTemplate jdbc;

    private ReminderScheduler scheduler;

    private final UUID    tenantId        = UUID.randomUUID();
    private final Instant appointmentTime = Instant.parse("2026-05-22T10:00:00Z");

    @BeforeEach
    void setUp() {
        scheduler = new ReminderScheduler(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    // ── scheduleReminders ─────────────────────────────────────────────────────

    @Test
    void scheduleReminders_insertsTwoRows() {
        scheduler.scheduleReminders(event());

        // Exactly two INSERT calls into scheduled_notifications
        verify(jdbc, times(2)).update(contains("scheduled_notifications"),
                anyString(), eq(tenantId), anyString(),
                any(Timestamp.class), anyString());
    }

    @Test
    void scheduleReminders_24hReminderSentAt_is24HoursBeforeAppointment() {
        ArgumentCaptor<Timestamp> tsCaptor = ArgumentCaptor.forClass(Timestamp.class);

        scheduler.scheduleReminders(event());

        verify(jdbc, times(2)).update(
                contains("scheduled_notifications"),
                anyString(), eq(tenantId), anyString(),
                tsCaptor.capture(), anyString()
        );

        List<Timestamp> allTs = tsCaptor.getAllValues();
        Instant expected24h = appointmentTime.minus(24, ChronoUnit.HOURS);
        // First invocation is the 24h reminder (see ReminderScheduler.scheduleReminders order)
        assertThat(allTs.get(0).toInstant()).isEqualTo(expected24h);
    }

    @Test
    void scheduleReminders_1hReminderSentAt_is1HourBeforeAppointment() {
        ArgumentCaptor<Timestamp> tsCaptor = ArgumentCaptor.forClass(Timestamp.class);

        scheduler.scheduleReminders(event());

        verify(jdbc, times(2)).update(
                contains("scheduled_notifications"),
                anyString(), eq(tenantId), anyString(),
                tsCaptor.capture(), anyString()
        );

        List<Timestamp> allTs = tsCaptor.getAllValues();
        Instant expected1h = appointmentTime.minus(1, ChronoUnit.HOURS);
        // Second invocation is the 1h reminder
        assertThat(allTs.get(1).toInstant()).isEqualTo(expected1h);
    }

    @Test
    void scheduleReminders_nullAppointmentTime_doesNotInsert() {
        AppointmentEvent event = event();
        event.setAppointmentTime(null);

        scheduler.scheduleReminders(event);

        verify(jdbc, never()).update(anyString(), (Object[]) any());
    }

    @Test
    void scheduleReminders_typeValues_are24hAnd1h() {
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);

        scheduler.scheduleReminders(event());

        verify(jdbc, times(2)).update(
                contains("scheduled_notifications"),
                anyString(), eq(tenantId), typeCaptor.capture(),
                any(Timestamp.class), anyString()
        );

        assertThat(typeCaptor.getAllValues()).containsExactly("24h", "1h");
    }

    // ── cancelReminders ───────────────────────────────────────────────────────

    @Test
    void cancelReminders_updatesScopedOnTenantId() {
        scheduler.cancelReminders("appt-cancel-001", tenantId);

        verify(jdbc).update(
                contains("UPDATE scheduled_notifications"),
                eq("appt-cancel-001"),
                eq(tenantId)
        );
    }

    @Test
    void cancelReminders_doesNotAffectOtherTenant() {
        UUID otherTenant = UUID.randomUUID();

        scheduler.cancelReminders("appt-cancel-001", tenantId);
        scheduler.cancelReminders("appt-cancel-001", otherTenant);

        verify(jdbc).update(contains("UPDATE scheduled_notifications"), eq("appt-cancel-001"), eq(tenantId));
        verify(jdbc).update(contains("UPDATE scheduled_notifications"), eq("appt-cancel-001"), eq(otherTenant));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AppointmentEvent event() {
        AppointmentEvent e = new AppointmentEvent();
        e.setTenantId(tenantId);
        e.setAppointmentUuid("appt-sched-001");
        e.setPatientUuid("patient-sched-001");
        e.setEventType(AppointmentEvent.EventType.SCHEDULED);
        e.setAppointmentTime(appointmentTime);
        e.setTimezone("Europe/Amsterdam");
        return e;
    }
}

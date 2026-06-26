package com.openmrs.notification.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.service.NotificationDispatcher;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FR-1f — ReminderDispatchJob: een reminder voor een afspraak die al begonnen is
 * (appointmentTime &lt; now) wordt overgeslagen (status='skipped') en NIET verstuurd;
 * een reminder voor een toekomstige afspraak wordt wél gedispatcht (status='sent').
 *
 * <p>Deze test dekt de skip-logica die de traceerbaarheidsmatrix aan FR-1f koppelt
 * maar die voorheen door geen enkele test werd geasserteerd.</p>
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes", "null"})
class ReminderDispatchJobTest {

    @Mock private JdbcTemplate           jdbc;
    @Mock private NotificationDispatcher dispatcher;
    @Mock private TenantService          tenantService;

    private ObjectMapper       mapper;
    private ReminderDispatchJob job;

    private final UUID   tenantId = UUID.randomUUID();
    private final String rowId    = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        job = new ReminderDispatchJob(jdbc, dispatcher, mapper, tenantService, new SimpleMeterRegistry());
    }

    // ── FR-1f: skip wanneer afspraak al begonnen is ───────────────────────────

    @Test
    void dispatch_appointmentAlreadyStarted_skipsAndDoesNotDispatch() throws Exception {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        stubDueRow("1h", past);
        when(tenantService.findById(any())).thenReturn(Optional.of(tenant()));

        job.dispatch();

        // FR-1f: geen verzending voor een reeds aangevangen afspraak
        verify(dispatcher, never()).dispatch(any());
        // En de rij wordt op 'skipped' gezet
        verify(jdbc).update(contains("skipped"), eq(rowId));
        verify(jdbc, never()).update(contains("'sent'"), eq(rowId));
    }

    // ── Tegenproef: toekomstige afspraak wordt wél verstuurd ───────────────────

    @Test
    void dispatch_appointmentInFuture_dispatchesAndMarksSent() throws Exception {
        Instant future = Instant.now().plus(2, ChronoUnit.HOURS);
        stubDueRow("1h", future);
        when(tenantService.findById(any())).thenReturn(Optional.of(tenant()));

        job.dispatch();

        verify(dispatcher).dispatch(any(AppointmentEvent.class));
        verify(jdbc).update(contains("'sent'"), eq(rowId));
        verify(jdbc, never()).update(contains("skipped"), eq(rowId));
    }

    @Test
    void dispatch_typeIs1h_setsReminder1hEventTypeOnDispatchedEvent() throws Exception {
        stubDueRow("1h", Instant.now().plus(2, ChronoUnit.HOURS));
        when(tenantService.findById(any())).thenReturn(Optional.of(tenant()));

        job.dispatch();

        ArgumentCaptor<AppointmentEvent> captor = ArgumentCaptor.forClass(AppointmentEvent.class);
        verify(dispatcher).dispatch(captor.capture());
        assertThat(captor.getValue().getEventType())
                .isEqualTo(AppointmentEvent.EventType.REMINDER_1H);
    }

    @Test
    void dispatch_noDueReminders_doesNothing() {
        when(jdbc.queryForList(anyString(), eq(20))).thenReturn(List.of());

        job.dispatch();

        verifyNoInteractions(dispatcher);
        verify(jdbc, never()).update(anyString(), (Object[]) any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubDueRow(String type, Instant appointmentTime) throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("id", rowId);
        row.put("tenant_id", tenantId);
        row.put("appointment_uuid", "appt-rd-001");
        row.put("type", type);
        row.put("payload", payloadJson(appointmentTime));
        when(jdbc.queryForList(anyString(), eq(20))).thenReturn((List) List.of(row));
    }

    private String payloadJson(Instant appointmentTime) throws Exception {
        AppointmentEvent e = new AppointmentEvent();
        e.setTenantId(tenantId);
        e.setAppointmentUuid("appt-rd-001");
        e.setPatientUuid("patient-rd-001");
        e.setEventType(AppointmentEvent.EventType.SCHEDULED);
        e.setAppointmentTime(appointmentTime);
        e.setTimezone("Europe/Amsterdam");
        return mapper.writeValueAsString(e);
    }

    private Tenant tenant() {
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setSlug("test-tenant");
        t.setTimezone("Europe/Amsterdam");
        return t;
    }
}

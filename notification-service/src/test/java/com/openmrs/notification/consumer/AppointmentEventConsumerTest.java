package com.openmrs.notification.consumer;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.scheduler.ReminderScheduler;
import com.openmrs.notification.service.NotificationDispatcher;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 6f — AppointmentEventConsumer: TenantContext wordt gezet, dispatcher + reminderScheduler worden
 * correct aangeroepen per eventType, events zonder tenantId worden overgeslagen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppointmentEventConsumerTest {

    @Mock private NotificationDispatcher dispatcher;
    @Mock private ReminderScheduler      reminderScheduler;
    @Mock private TenantService          tenantService;

    private AppointmentEventConsumer consumer;
    private Tenant                   tenant;

    @BeforeEach
    void setUp() {
        consumer = new AppointmentEventConsumer(dispatcher, reminderScheduler, tenantService);

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setSlug("test-tenant");

        when(tenantService.findById(tenant.getId())).thenReturn(Optional.of(tenant));
    }

    @Test
    void onAppointment_scheduled_dispatchesAndSchedulesReminders() {
        AppointmentEvent event = event(AppointmentEvent.EventType.SCHEDULED);

        consumer.onAppointment(event);

        verify(dispatcher).dispatch(event);
        verify(reminderScheduler).scheduleReminders(event);
        verify(reminderScheduler, never()).cancelReminders(any(), any());
    }

    @Test
    void onAppointment_updated_cancelsAndReschedulesReminders() {
        AppointmentEvent event = event(AppointmentEvent.EventType.UPDATED);

        consumer.onAppointment(event);

        verify(dispatcher).dispatch(event);
        verify(reminderScheduler).cancelReminders(event.getAppointmentUuid(), tenant.getId());
        verify(reminderScheduler).scheduleReminders(event);
    }

    @Test
    void onAppointment_reminder24h_dispatchesWithoutScheduling() {
        AppointmentEvent event = event(AppointmentEvent.EventType.REMINDER_24H);

        consumer.onAppointment(event);

        verify(dispatcher).dispatch(event);
        verify(reminderScheduler, never()).scheduleReminders(any());
        verify(reminderScheduler, never()).cancelReminders(any(), any());
    }

    @Test
    void onCancellation_dispatchesAndCancelsReminders() {
        AppointmentEvent event = event(AppointmentEvent.EventType.CANCELLED);

        consumer.onCancellation(event);

        verify(dispatcher).dispatch(event);
        verify(reminderScheduler).cancelReminders(event.getAppointmentUuid(), tenant.getId());
        verify(reminderScheduler, never()).scheduleReminders(any());
    }

    @Test
    void onAppointment_nullTenantId_skipsProcessing() {
        AppointmentEvent event = new AppointmentEvent();
        event.setEventType(AppointmentEvent.EventType.SCHEDULED);
        event.setTenantId(null);

        consumer.onAppointment(event);

        verify(dispatcher, never()).dispatch(any());
        verify(reminderScheduler, never()).scheduleReminders(any());
    }

    @Test
    void onAppointment_tenantNotFound_skipsProcessing() {
        UUID unknownId = UUID.randomUUID();
        when(tenantService.findById(unknownId)).thenReturn(Optional.empty());

        AppointmentEvent event = new AppointmentEvent();
        event.setTenantId(unknownId);
        event.setEventType(AppointmentEvent.EventType.SCHEDULED);

        consumer.onAppointment(event);

        verify(dispatcher, never()).dispatch(any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AppointmentEvent event(AppointmentEvent.EventType type) {
        AppointmentEvent e = new AppointmentEvent();
        e.setTenantId(tenant.getId());
        e.setAppointmentUuid("appt-consumer-001");
        e.setPatientUuid("patient-consumer-001");
        e.setEventType(type);
        return e;
    }
}

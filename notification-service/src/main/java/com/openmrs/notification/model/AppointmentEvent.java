package com.openmrs.notification.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Canonical internal representation of an OpenMRS appointment event.
 * Deserialized from RabbitMQ messages AND constructed by the polling reconciler.
 * Keeping a single model prevents duplication between the two ingestion paths.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppointmentEvent {

    public enum EventType {
        SCHEDULED, UPDATED, CANCELLED
    }

    private String appointmentUuid;
    private String patientUuid;
    private String patientName;
    private String patientPhone;
    private String patientEmail;
    private Instant appointmentTime;
    private String providerName;
    private String locationName;
    private EventType eventType;
    private Instant occurredAt;

    // ── Getters & setters ────────────────────────────────────────────────

    public String getAppointmentUuid() { return appointmentUuid; }
    public void setAppointmentUuid(String appointmentUuid) { this.appointmentUuid = appointmentUuid; }

    public String getPatientUuid() { return patientUuid; }
    public void setPatientUuid(String patientUuid) { this.patientUuid = patientUuid; }

    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }

    public String getPatientPhone() { return patientPhone; }
    public void setPatientPhone(String patientPhone) { this.patientPhone = patientPhone; }

    public String getPatientEmail() { return patientEmail; }
    public void setPatientEmail(String patientEmail) { this.patientEmail = patientEmail; }

    public Instant getAppointmentTime() { return appointmentTime; }
    public void setAppointmentTime(Instant appointmentTime) { this.appointmentTime = appointmentTime; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getLocationName() { return locationName; }
    public void setLocationName(String locationName) { this.locationName = locationName; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}

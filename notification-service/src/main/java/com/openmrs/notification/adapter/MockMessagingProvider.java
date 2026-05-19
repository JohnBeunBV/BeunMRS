package com.openmrs.notification.adapter;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationChannel;
import com.openmrs.notification.model.NotificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter for the mock messaging container used during development.
 * The mock service exposes a REST endpoint that accepts a generic
 * notification payload and returns a message ID.
 *
 * In sprint 3 this can be replaced (or complemented) by real provider
 * adapters (e.g. TwilioSmsProvider, SendGridEmailProvider) without
 * touching any other class.
 */
@Component
public class MockMessagingProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(MockMessagingProvider.class);

    private final RestTemplate restTemplate;
    private final String mockBaseUrl;
    private final boolean enabled;

    public MockMessagingProvider(
            RestTemplate restTemplate,
            @Value("${mock.messaging.base-url:http://mock-messaging:8025}") String mockBaseUrl,
            @Value("${mock.messaging.enabled:true}") boolean enabled) {
        this.restTemplate = restTemplate;
        this.mockBaseUrl  = mockBaseUrl;
        this.enabled      = enabled;
    }

    @Override
    public NotificationChannel channel() {
        // Mock covers all channels during development
        return NotificationChannel.SMS;
    }

    @Override
    public String providerName() {
        return "mock-messaging";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public NotificationResult send(AppointmentEvent event) {
        try {
            String message = buildMessage(event);

            Map<String, Object> payload = Map.of(
                    "to",        event.getPatientPhone() != null ? event.getPatientPhone() : event.getPatientEmail(),
                    "message",   message,
                    "channel",   deriveChannel(event),
                    "reference", event.getAppointmentUuid()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    mockBaseUrl + "/api/send", request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String msgId = (String) response.getBody().getOrDefault("messageId", UUID.randomUUID().toString());
                log.info("Mock send OK — appointment={} msgId={}", event.getAppointmentUuid(), msgId);
                return NotificationResult.ok(msgId);
            } else {
                return NotificationResult.failure("Mock returned HTTP " + response.getStatusCode());
            }

        } catch (Exception ex) {
            log.error("Mock send failed — appointment={}", event.getAppointmentUuid(), ex);
            return NotificationResult.failure(ex.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String buildMessage(AppointmentEvent event) {
        String name = event.getPatientName() != null ? event.getPatientName() : "Patient";
        String time = event.getAppointmentTime() != null
                ? DateTimeFormatter.RFC_1123_DATE_TIME.format(event.getAppointmentTime().atZone(java.time.ZoneOffset.UTC))
                : "TBD";

        return switch (event.getEventType()) {
            case SCHEDULED -> String.format(
                    "Hi %s, your appointment with %s at %s is confirmed for %s.",
                    name, event.getProviderName(), event.getLocationName(), time);
            case UPDATED -> String.format(
                    "Hi %s, your appointment has been rescheduled to %s.",
                    name, time);
            case CANCELLED -> String.format(
                    "Hi %s, your appointment on %s has been cancelled. Please contact us to reschedule.",
                    name, time);
        };
    }

    private String deriveChannel(AppointmentEvent event) {
        if (event.getPatientPhone() != null) return "sms";
        if (event.getPatientEmail() != null) return "email";
        return "unknown";
    }
}

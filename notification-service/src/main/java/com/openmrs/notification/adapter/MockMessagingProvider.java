package com.openmrs.notification.adapter;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationChannel;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;
import com.openmrs.notification.util.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

@Component
public class MockMessagingProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(MockMessagingProvider.class);

    private final RestTemplate restTemplate;
    private final String       mockBaseUrl;
    private final boolean      enabled;

    public MockMessagingProvider(
            @Qualifier("providerRestTemplate") RestTemplate restTemplate,
            @Value("${mock.messaging.base-url:http://mock-messaging:8025}") String mockBaseUrl,
            @Value("${mock.messaging.enabled:true}") boolean enabled) {
        this.restTemplate = restTemplate;
        this.mockBaseUrl  = mockBaseUrl;
        this.enabled      = enabled;
    }

    @Override public NotificationChannel channel() { return NotificationChannel.SMS; }
    @Override public String providerName()          { return "mock-messaging"; }
    @Override public boolean isEnabled()            { return enabled; }

    @Override
    public NotificationResult send(AppointmentEvent event, ProviderCredentials credentials) {
        if (event.getPatientPhone() == null) {
            log.warn("[Mock] Cannot send — patient has no phone number — appointment={}",
                    event.getAppointmentUuid());
            return NotificationResult.failure("Patient has no phone number");
        }

        try {
            Map<String, Object> payload = Map.of(
                    "to",        event.getPatientPhone(),
                    "message",   buildMessage(event),
                    "channel",   "sms",
                    "reference", event.getAppointmentUuid()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    mockBaseUrl + "/api/send",
                    new HttpEntity<>(payload, headers),
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String msgId = (String) response.getBody().getOrDefault("messageId", UUID.randomUUID().toString());
                log.info("Mock send OK — appointment={} msgId={}", event.getAppointmentUuid(), msgId);
                return NotificationResult.ok(msgId);
            }
            return NotificationResult.failure("Mock returned HTTP " + response.getStatusCode());

        } catch (Exception ex) {
            log.error("Mock send failed — appointment={}", event.getAppointmentUuid(), ex);
            return NotificationResult.failure(ex.getMessage());
        }
    }

    private String buildMessage(AppointmentEvent event) {
        String name     = event.getPatientName() != null ? event.getPatientName() : "Patient";
        String time     = MessageHelper.formatTime(event.getAppointmentTime(), event.getTimezone());
        String loc      = MessageHelper.locationSuffix(event.getLocationName());
        String comments = MessageHelper.commentsSuffix(event.getComments());
        return switch (event.getEventType()) {
            case SCHEDULED    -> String.format("Hi %s, uw afspraak op %s%s is bevestigd.%s", name, time, loc, comments);
            case UPDATED      -> String.format("Hi %s, uw afspraak is gewijzigd naar %s%s.%s", name, time, loc, comments);
            case CANCELLED    -> String.format("Hi %s, uw afspraak op %s is geannuleerd.", name, time);
            case REMINDER_24H -> String.format("Hi %s, herinnering: uw afspraak is morgen om %s%s.%s", name, time, loc, comments);
            case REMINDER_1H  -> String.format("Hi %s, herinnering: uw afspraak is over een uur (%s)%s.%s", name, time, loc, comments);
        };
    }

}

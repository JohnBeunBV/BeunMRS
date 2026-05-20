package com.openmrs.notification.adapter.swiftsend;

import com.openmrs.notification.adapter.NotificationProvider;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationChannel;
import com.openmrs.notification.model.NotificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.openmrs.notification.util.MessageHelper;

import java.util.Map;

/**
 * SwiftSend — REST API authenticated with X-API-KEY header.
 * Handles rate limiting (429) and random fault injection from FakeComWorld.
 */
@Component
public class SwiftSendProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SwiftSendProvider.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String studentGroup;

    public SwiftSendProvider(
            @Qualifier("providerRestTemplate") RestTemplate restTemplate,
            @Value("${fakecomworld.base-url:http://fakecomworld:8080}") String baseUrl,
            @Value("${provider.swiftsend.api-key:your-api-key-here}") String apiKey,
            @Value("${fakecomworld.student-group:group-1}") String studentGroup) {
        this.restTemplate = restTemplate;
        this.baseUrl      = baseUrl;
        this.apiKey       = apiKey;
        this.studentGroup = studentGroup;
    }

    @Override public NotificationChannel channel()    { return NotificationChannel.SMS; }
    @Override public String providerName()             { return "SwiftSend"; }

    @Override
    public NotificationResult send(AppointmentEvent event) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY",       apiKey);
            headers.set("X-STUDENT-GROUP", studentGroup);

            // recipients is een array; content is de berichttekst
            String recipient = event.getPatientPhone() != null ? event.getPatientPhone() : "unknown";
            log.debug("[SwiftSend] Sturen naar {} — appointment={}", MessageHelper.mask(recipient), event.getAppointmentUuid());
            Map<String, Object> body = Map.of(
                    "recipients", new String[]{ recipient },
                    "content",    buildMessage(event)
            );

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/swiftsend",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String id = String.valueOf(resp.getBody().getOrDefault("messageId", "unknown"));
                log.info("[SwiftSend] Sent OK — appointment={} msgId={}", event.getAppointmentUuid(), id);
                return NotificationResult.ok(id);
            }
            return NotificationResult.failure("HTTP " + resp.getStatusCode());

        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("[SwiftSend] Rate limited — will retry via RabbitMQ back-off");
            return NotificationResult.failure("Rate limited (429)");
        } catch (Exception ex) {
            log.error("[SwiftSend] Send failed — appointment={}", event.getAppointmentUuid(), ex);
            return NotificationResult.failure(ex.getMessage());
        }
    }

    private String buildMessage(AppointmentEvent event) {
        String time     = MessageHelper.formatTime(event.getAppointmentTime());
        String loc      = MessageHelper.locationSuffix(event.getLocationName());
        String comments = MessageHelper.commentsSuffix(event.getComments());
        return switch (event.getEventType()) {
            case SCHEDULED    -> String.format("Uw afspraak op %s%s is bevestigd.%s", time, loc, comments);
            case UPDATED      -> String.format("Uw afspraak is gewijzigd naar %s%s.%s", time, loc, comments);
            case CANCELLED    -> String.format("Uw afspraak op %s is geannuleerd. Neem contact op om opnieuw in te plannen.", time);
            case REMINDER_24H -> String.format("Herinnering: uw afspraak is morgen om %s%s.%s", time, loc, comments);
            case REMINDER_1H  -> String.format("Herinnering: uw afspraak is over een uur (%s)%s.%s", time, loc, comments);
        };
    }
}
package com.openmrs.notification.adapter.legacylink;

import com.openmrs.notification.adapter.NotificationProvider;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * LegacyLink — SOAP API with Basic authentication.
 *
 * credentials.apiKey()  = username
 * credentials.extra()   = password
 */
@Component
public class LegacyLinkProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(LegacyLinkProvider.class);

    private final RestTemplate restTemplate;
    private final String       baseUrl;
    private final String       studentGroup;

    public LegacyLinkProvider(
            @Qualifier("providerRestTemplate") RestTemplate restTemplate,
            @Value("${fakecomworld.base-url:http://fakecomworld:8080}") String baseUrl,
            @Value("${fakecomworld.student-group:group-1}") String studentGroup) {
        this.restTemplate = restTemplate;
        this.baseUrl      = baseUrl;
        this.studentGroup = studentGroup;
    }

    @Override public NotificationChannel channel() { return NotificationChannel.SMS; }
    @Override public String providerName()          { return "LegacyLink"; }

    @Override
    public NotificationResult send(AppointmentEvent event, ProviderCredentials credentials) {
        try {
            String correlationId = UUID.randomUUID().toString();
            log.debug("[LegacyLink] Sturen naar {} — appointment={}",
                    MessageHelper.mask(event.getPatientPhone()), event.getAppointmentUuid());

            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                    (credentials.apiKey() + ":" + credentials.extra()).getBytes(StandardCharsets.UTF_8));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_XML);
            headers.set("Accept",          MediaType.APPLICATION_XML_VALUE);
            headers.set("Authorization",   basicAuth);
            headers.set("X-STUDENT-GROUP", studentGroup);

            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl + "/LegacyLink/SendSms",
                    HttpMethod.POST,
                    new HttpEntity<>(buildSoapEnvelope(event, correlationId), headers),
                    String.class
            );

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String msgId = extractMessageId(resp.getBody(), correlationId);
                log.info("[LegacyLink] Sent OK — appointment={} msgId={}", event.getAppointmentUuid(), msgId);
                return NotificationResult.ok(msgId);
            }
            return NotificationResult.failure("SOAP HTTP " + resp.getStatusCode());

        } catch (Exception ex) {
            log.error("[LegacyLink] Send failed — appointment={}", event.getAppointmentUuid(), ex);
            return NotificationResult.failure(ex.getMessage());
        }
    }

    private String buildSoapEnvelope(AppointmentEvent event, String correlationId) {
        String recipient = event.getPatientPhone() != null
                ? event.getPatientPhone()
                : (event.getPatientEmail() != null ? event.getPatientEmail() : "unknown");
        String time     = MessageHelper.formatTime(event.getAppointmentTime());
        String loc      = MessageHelper.locationSuffix(event.getLocationName());
        String comments = MessageHelper.commentsSuffix(event.getComments());
        String message  = switch (event.getEventType()) {
            case SCHEDULED    -> String.format("Afspraak bevestigd op %s%s.%s", time, loc, comments);
            case UPDATED      -> String.format("Afspraak gewijzigd naar %s%s.%s", time, loc, comments);
            case CANCELLED    -> String.format("Uw afspraak op %s is geannuleerd.", time);
            case REMINDER_24H -> String.format("Herinnering: uw afspraak is morgen om %s%s.%s", time, loc, comments);
            case REMINDER_1H  -> String.format("Herinnering: uw afspraak is over een uur (%s)%s.%s", time, loc, comments);
        };

        return """
            <?xml version="1.0" encoding="utf-8"?>
            <SendSmsRequest xmlns="http://legacylink.fakecomworld.com/v1">
              <PhoneNumber>%s</PhoneNumber>
              <MessageText>%s</MessageText>
              <SenderIdentification>BeunMRS</SenderIdentification>
            </SendSmsRequest>
            """.formatted(xmlEscape(recipient), xmlEscape(message));
    }

    private String extractMessageId(String xmlResponse, String fallback) {
        int start = xmlResponse.indexOf("<MessageReference>");
        int end   = xmlResponse.indexOf("</MessageReference>");
        if (start >= 0 && end > start) {
            return xmlResponse.substring(start + "<MessageReference>".length(), end);
        }
        return fallback;
    }

    private String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

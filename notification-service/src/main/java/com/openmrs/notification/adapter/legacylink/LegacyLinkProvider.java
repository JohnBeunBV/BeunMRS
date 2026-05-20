package com.openmrs.notification.adapter.legacylink;

import com.openmrs.notification.adapter.NotificationProvider;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationChannel;
import com.openmrs.notification.model.NotificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * LegacyLink — SOAP API with BASIC authentication.
 *
 * FakeComWorld exposes a SOAP endpoint with simulated delays of 100–3000 ms.
 * This adapter builds the SOAP envelope manually (no need for a full WSDL
 * client for a single operation) and parses the response with basic string ops
 * to avoid pulling in a heavy JAXB/CXF dependency.
 *
 * The adapter uses a dedicated RestTemplate with a longer read timeout (5s)
 * to account for LegacyLink's high simulated latency.
 */
@Component
public class LegacyLinkProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(LegacyLinkProvider.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String basicAuthHeader;
    private final String studentGroup;

    public LegacyLinkProvider(
            RestTemplate restTemplate,
            @Value("${fakecomworld.base-url:http://fakecomworld:8080}") String baseUrl,
            @Value("${provider.legacylink.username:legacylink-user}") String username,
            @Value("${provider.legacylink.password:legacylink-password}") String password,
            @Value("${fakecomworld.student-group:group-1}") String studentGroup) {
        this.restTemplate    = restTemplate;
        this.baseUrl         = baseUrl;
        this.studentGroup    = studentGroup;
        this.basicAuthHeader = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override public NotificationChannel channel() { return NotificationChannel.SMS; }
    @Override public String providerName()          { return "LegacyLink"; }

    @Override
    public NotificationResult send(AppointmentEvent event) {
        try {
            String correlationId = UUID.randomUUID().toString();
            String soapBody = buildSoapEnvelope(event, correlationId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_XML);
            headers.set("Authorization",    basicAuthHeader);
            headers.set("SOAPAction",       "SendMessage");
            headers.set("X-STUDENT-GROUP",  studentGroup);

            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl + "/LegacyLink/SendSms",
                    HttpMethod.POST,
                    new HttpEntity<>(soapBody, headers),
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

    // ── SOAP helpers ──────────────────────────────────────────────────────────

    private String buildSoapEnvelope(AppointmentEvent event, String correlationId) {
        String recipient = event.getPatientPhone() != null
                ? event.getPatientPhone()
                : (event.getPatientEmail() != null ? event.getPatientEmail() : "unknown");
        String message = switch (event.getEventType()) {
            case SCHEDULED    -> String.format("Afspraak bevestigd op %s", event.getAppointmentTime());
            case UPDATED      -> String.format("Afspraak gewijzigd naar %s", event.getAppointmentTime());
            case CANCELLED    -> "Uw afspraak is geannuleerd.";
            case REMINDER_24H -> String.format("Herinnering: uw afspraak is morgen om %s.", event.getAppointmentTime());
            case REMINDER_1H  -> String.format("Herinnering: uw afspraak is over een uur om %s.", event.getAppointmentTime());
        };

        // Geen SOAP envelope — FakeComWorld verwacht plain XML met dit namespace
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
        // LegacyLink antwoordt met <MessageReference>LGC-...</MessageReference>
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

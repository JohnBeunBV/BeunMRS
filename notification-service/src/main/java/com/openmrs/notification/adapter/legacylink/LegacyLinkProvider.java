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
                    baseUrl + "/api/legacylink/soap",
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
            case SCHEDULED -> String.format("Afspraak bevestigd op %s", event.getAppointmentTime());
            case UPDATED   -> String.format("Afspraak gewijzigd naar %s", event.getAppointmentTime());
            case CANCELLED -> "Uw afspraak is geannuleerd.";
        };

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <soapenv:Envelope
                xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                xmlns:ll="http://legacylink.fakecomworld/messaging">
              <soapenv:Header/>
              <soapenv:Body>
                <ll:SendMessageRequest>
                  <ll:CorrelationId>%s</ll:CorrelationId>
                  <ll:Recipient>%s</ll:Recipient>
                  <ll:Message>%s</ll:Message>
                  <ll:Reference>%s</ll:Reference>
                </ll:SendMessageRequest>
              </soapenv:Body>
            </soapenv:Envelope>
            """.formatted(correlationId, xmlEscape(recipient), xmlEscape(message), event.getAppointmentUuid());
    }

    private String extractMessageId(String soapResponse, String fallback) {
        // Simple extraction — look for <MessageId>...</MessageId> in the response
        int start = soapResponse.indexOf("<MessageId>");
        int end   = soapResponse.indexOf("</MessageId>");
        if (start >= 0 && end > start) {
            return soapResponse.substring(start + "<MessageId>".length(), end);
        }
        return fallback;
    }

    private String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

package com.openmrs.notification.adapter.securepost;

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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SecurePost — email via JWT-authenticated REST API.
 *
 * credentials.apiKey()  = clientId
 * credentials.extra()   = clientSecret
 *
 * Token cache is keyed by clientId so multiple tenants can use SecurePost
 * simultaneously with their own tokens.
 */
@Component
public class SecurePostProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SecurePostProvider.class);
    private static final long   TOKEN_REFRESH_BUFFER_SECONDS = 30;

    private final RestTemplate restTemplate;
    private final String       baseUrl;
    private final String       studentGroup;

    // Per-clientId token cache (thread-safe)
    private final ConcurrentHashMap<String, TokenEntry> tokenCache = new ConcurrentHashMap<>();

    public SecurePostProvider(
            @Qualifier("providerRestTemplate") RestTemplate restTemplate,
            @Value("${fakecomworld.base-url:http://fakecomworld:8080}") String baseUrl,
            @Value("${fakecomworld.student-group:group-1}") String studentGroup) {
        this.restTemplate = restTemplate;
        this.baseUrl      = baseUrl;
        this.studentGroup = studentGroup;
    }

    @Override public NotificationChannel channel() { return NotificationChannel.SMS; }
    @Override public String providerName()          { return "SecurePost"; }

    @Override
    public NotificationResult send(AppointmentEvent event, ProviderCredentials credentials) {
        String clientId     = credentials.apiKey();
        String clientSecret = credentials.extra();

        try {
            String token  = getValidToken(clientId, clientSecret);
            NotificationResult result = doSend(event, token);

            if (!result.isSuccess() && result.getErrorMessage() != null
                    && result.getErrorMessage().contains("401")) {
                log.info("[SecurePost] Got 401 — refreshing token and retrying");
                tokenCache.remove(clientId);
                token  = getValidToken(clientId, clientSecret);
                result = doSend(event, token);
            }
            return result;

        } catch (Exception ex) {
            log.error("[SecurePost] Send failed — appointment={}", event.getAppointmentUuid(), ex);
            return NotificationResult.failure(ex.getMessage());
        }
    }

    private NotificationResult doSend(AppointmentEvent event, String token) {
        if (event.getPatientPhone() == null) {
            log.warn("[SecurePost] Cannot send — patient has no phone number — appointment={}",
                    event.getAppointmentUuid());
            return NotificationResult.failure("Patient has no phone number");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            headers.set("X-STUDENT-GROUP", studentGroup);

            String recipient = event.getPatientPhone();
            log.debug("[SecurePost] Sturen naar {} — appointment={}",
                    MessageHelper.mask(recipient), event.getAppointmentUuid());

            Map<String, Object> body = Map.of(
                    "format",    "SMS",
                    "recipient", recipient,
                    "body",      buildMessage(event)
            );

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/securepost/message",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String id = String.valueOf(resp.getBody().getOrDefault("trackingId", "unknown"));
                log.info("[SecurePost] Sent OK — appointment={} msgId={}", event.getAppointmentUuid(), id);
                return NotificationResult.ok(id);
            }
            return NotificationResult.failure("HTTP " + resp.getStatusCode());

        } catch (HttpClientErrorException.Unauthorized e) {
            return NotificationResult.failure("401 Unauthorized");
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("[SecurePost] Rate limited");
            return NotificationResult.failure("Rate limited (429)");
        } catch (Exception ex) {
            return NotificationResult.failure(ex.getMessage());
        }
    }

    private synchronized String getValidToken(String clientId, String clientSecret) {
        TokenEntry entry = tokenCache.get(clientId);
        Instant now = Instant.now();
        if (entry != null && now.isBefore(entry.expiresAt().minusSeconds(TOKEN_REFRESH_BUFFER_SECONDS))) {
            return entry.token();
        }
        TokenEntry fresh = fetchToken(clientId, clientSecret);
        tokenCache.put(clientId, fresh);
        return fresh.token();
    }

    @SuppressWarnings("unchecked")
    private TokenEntry fetchToken(String clientId, String clientSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-STUDENT-GROUP", studentGroup);

        Map<String, String> body = Map.of(
                "clientId",     clientId,
                "clientSecret", clientSecret
        );

        ResponseEntity<Map> resp = restTemplate.exchange(
                baseUrl + "/securepost/auth",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );

        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new RuntimeException("Failed to obtain SecurePost JWT: HTTP " + resp.getStatusCode());
        }

        String token     = (String) resp.getBody().get("accessToken");
        int    expiresIn = (int) resp.getBody().getOrDefault("expiresIn", 180);
        log.debug("[SecurePost] Token cached for clientId={}, expires in {}s", clientId, expiresIn);
        return new TokenEntry(token, Instant.now().plusSeconds(expiresIn));
    }

    private String buildMessage(AppointmentEvent event) {
        String time     = MessageHelper.formatTime(event.getAppointmentTime(), event.getTimezone());
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

    private record TokenEntry(String token, Instant expiresAt) {}
}

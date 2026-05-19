package com.openmrs.notification.adapter.securepost;

import com.openmrs.notification.adapter.NotificationProvider;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationChannel;
import com.openmrs.notification.model.NotificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * SecurePost — REST API with JWT authentication.
 *
 * JWT tokens expire after 3 minutes (FakeComWorld default).
 * This adapter caches the token and refreshes it proactively 30s before expiry.
 * On 401 responses it immediately fetches a new token and retries once.
 */
@Component
public class SecurePostProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SecurePostProvider.class);
    private static final long TOKEN_REFRESH_BUFFER_SECONDS = 30;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String studentGroup;

    // Token cache
    private volatile String  cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public SecurePostProvider(
            RestTemplate restTemplate,
            @Value("${fakecomworld.base-url:http://fakecomworld:8080}") String baseUrl,
            @Value("${provider.securepost.client-id:securepost-client-id}") String clientId,
            @Value("${provider.securepost.client-secret:securepost-secret-key}") String clientSecret,
            @Value("${fakecomworld.student-group:group-1}") String studentGroup) {
        this.restTemplate = restTemplate;
        this.baseUrl      = baseUrl;
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
        this.studentGroup = studentGroup;
    }

    @Override public NotificationChannel channel() { return NotificationChannel.EMAIL; }
    @Override public String providerName()          { return "SecurePost"; }

    @Override
    public NotificationResult send(AppointmentEvent event) {
        try {
            String token = getValidToken();
            NotificationResult result = doSend(event, token);

            // If 401, refresh token and retry once
            if (!result.isSuccess() && result.getErrorMessage() != null
                    && result.getErrorMessage().contains("401")) {
                log.info("[SecurePost] Got 401 — refreshing token and retrying");
                cachedToken    = null;
                tokenExpiresAt = Instant.EPOCH;
                token  = getValidToken();
                result = doSend(event, token);
            }
            return result;

        } catch (Exception ex) {
            log.error("[SecurePost] Send failed — appointment={}", event.getAppointmentUuid(), ex);
            return NotificationResult.failure(ex.getMessage());
        }
    }

    private NotificationResult doSend(AppointmentEvent event, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            headers.set("X-STUDENT-GROUP", studentGroup);

            Map<String, Object> body = Map.of(
                    "to",        event.getPatientEmail() != null ? event.getPatientEmail() : "unknown@example.com",
                    "subject",   subjectFor(event),
                    "body",      buildMessage(event),
                    "reference", event.getAppointmentUuid()
            );

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/api/securepost/messages",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String id = String.valueOf(resp.getBody().getOrDefault("messageId", "unknown"));
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

    // ── Token management ──────────────────────────────────────────────────────

    private synchronized String getValidToken() {
        Instant now = Instant.now();
        if (cachedToken != null && now.isBefore(tokenExpiresAt.minusSeconds(TOKEN_REFRESH_BUFFER_SECONDS))) {
            return cachedToken;
        }
        log.debug("[SecurePost] Fetching new JWT token");
        fetchToken();
        return cachedToken;
    }

    @SuppressWarnings("unchecked")
    private void fetchToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-STUDENT-GROUP", studentGroup);

        Map<String, String> body = Map.of(
                "clientId",     clientId,
                "clientSecret", clientSecret
        );

        ResponseEntity<Map> resp = restTemplate.exchange(
                baseUrl + "/api/securepost/auth/token",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );

        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new RuntimeException("Failed to obtain SecurePost JWT: HTTP " + resp.getStatusCode());
        }

        cachedToken    = (String) resp.getBody().get("token");
        // FakeComWorld returns expiresIn in seconds
        int expiresIn  = (int) resp.getBody().getOrDefault("expiresIn", 180);
        tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
        log.debug("[SecurePost] Token cached, expires in {}s", expiresIn);
    }

    private String subjectFor(AppointmentEvent event) {
        return switch (event.getEventType()) {
            case SCHEDULED -> "Afspraakbevestiging";
            case UPDATED   -> "Afspraak gewijzigd";
            case CANCELLED -> "Afspraak geannuleerd";
        };
    }

    private String buildMessage(AppointmentEvent event) {
        return switch (event.getEventType()) {
            case SCHEDULED -> String.format("Uw afspraak op %s is bevestigd.", event.getAppointmentTime());
            case UPDATED   -> String.format("Uw afspraak is gewijzigd naar %s.", event.getAppointmentTime());
            case CANCELLED -> "Uw afspraak is geannuleerd. Neem contact op om opnieuw in te plannen.";
        };
    }
}

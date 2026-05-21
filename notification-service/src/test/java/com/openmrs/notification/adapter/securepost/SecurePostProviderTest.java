package com.openmrs.notification.adapter.securepost;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 6c — SecurePostProvider: token caching per clientId, retry op 401.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class SecurePostProviderTest {

    @Mock private RestTemplate restTemplate;

    private SecurePostProvider  provider;
    private ProviderCredentials credentials;

    @BeforeEach
    void setUp() {
        provider    = new SecurePostProvider(restTemplate, "http://fakecomworld:8080", "group-1");
        credentials = new ProviderCredentials("client-id-1", "client-secret-1");
    }

    @Test
    void send_fetchesTokenAndSendsMessage() {
        stubTokenResponse("jwt-token-abc", 180);
        stubMessageResponse("track-001");

        NotificationResult result = provider.send(event(), credentials);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProviderMessageId()).isEqualTo("track-001");
        // auth endpoint called once
        verify(restTemplate, times(1)).exchange(contains("/securepost/auth"), any(), any(), eq(Map.class));
    }

    @Test
    void send_reusesCachedToken_onSecondCall() {
        stubTokenResponse("cached-token", 180);
        stubMessageResponse("track-002");

        provider.send(event(), credentials);
        provider.send(event(), credentials);

        // Token should only be fetched once
        verify(restTemplate, times(1)).exchange(contains("/securepost/auth"), any(), any(), eq(Map.class));
        verify(restTemplate, times(2)).exchange(contains("/securepost/message"), any(), any(), eq(Map.class));
    }

    @Test
    void send_refreshesTokenForDifferentClientId() {
        stubTokenResponse("token-client1", 180);
        stubMessageResponse("track-003");

        ProviderCredentials creds2 = new ProviderCredentials("client-id-2", "secret-2");
        provider.send(event(), credentials);
        provider.send(event(), creds2);

        // Two different clientIds → two separate token fetches
        verify(restTemplate, times(2)).exchange(contains("/securepost/auth"), any(), any(), eq(Map.class));
    }

    @Test
    void send_retryOn401_fetchesNewToken() {
        // Token endpoint returns a valid token on both calls (initial + post-401 retry)
        stubTokenResponse("fresh-token", 180);

        // First message call → 401, second → success
        ResponseEntity<Map> okResp = ResponseEntity.ok(Map.of("trackingId", "track-004"));

        when(restTemplate.exchange(contains("/securepost/message"), any(), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.Unauthorized.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null))
                .thenReturn(okResp);

        NotificationResult result = provider.send(event(), credentials);

        assertThat(result.isSuccess()).isTrue();
        verify(restTemplate, times(2)).exchange(contains("/securepost/auth"), any(), any(), eq(Map.class));
    }

    @Test
    void providerName_isSecurePost() {
        assertThat(provider.providerName()).isEqualTo("SecurePost");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubTokenResponse(String token, int expiresIn) {
        ResponseEntity<Map> tokenResp = ResponseEntity.ok(
                Map.of("accessToken", token, "expiresIn", expiresIn));
        when(restTemplate.exchange(contains("/securepost/auth"), any(), any(), eq(Map.class)))
                .thenReturn(tokenResp);
    }

    private void stubMessageResponse(String trackingId) {
        ResponseEntity<Map> msgResp = ResponseEntity.ok(Map.of("trackingId", trackingId));
        when(restTemplate.exchange(contains("/securepost/message"), any(), any(), eq(Map.class)))
                .thenReturn(msgResp);
    }

    private AppointmentEvent event() {
        AppointmentEvent e = new AppointmentEvent();
        e.setAppointmentUuid("appt-sp-001");
        e.setPatientUuid("patient-sp-001");
        e.setPatientEmail("patient@example.com");
        e.setEventType(AppointmentEvent.EventType.SCHEDULED);
        e.setTimezone("Europe/Amsterdam");
        return e;
    }
}

package com.openmrs.notification.adapter.swiftsend;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 6b — SwiftSendProvider: X-API-KEY header aanwezig, berichtinhoud klopt, 429 levert failure op.
 */
@ExtendWith(MockitoExtension.class)
class SwiftSendProviderTest {

    @Mock private RestTemplate restTemplate;

    private SwiftSendProvider provider;
    private ProviderCredentials credentials;

    @BeforeEach
    void setUp() {
        provider    = new SwiftSendProvider(restTemplate, "http://fakecomworld:8080", "group-1");
        credentials = new ProviderCredentials("sk-swift-test-key", null);
    }

    @Test
    void send_scheduled_returnsSuccess() {
        stubOkResponse("msg-id-1");

        NotificationResult result = provider.send(scheduledEvent(), credentials);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProviderMessageId()).isEqualTo("msg-id-1");
    }

    @Test
    void send_includesApiKeyHeader() {
        stubOkResponse("msg-id-2");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        provider.send(scheduledEvent(), credentials);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
        HttpHeaders headers = captor.getValue().getHeaders();
        assertThat(headers.getFirst("X-API-KEY")).isEqualTo("sk-swift-test-key");
        assertThat(headers.getFirst("X-STUDENT-GROUP")).isEqualTo("group-1");
    }

    @Test
    void send_messageContainsAppointmentTime() {
        stubOkResponse("msg-id-3");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        AppointmentEvent event = scheduledEvent();
        event.setAppointmentTime(Instant.parse("2026-05-22T10:00:00Z"));
        event.setTimezone("Europe/Amsterdam");
        provider.send(event, credentials);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertThat(body.get("content").toString()).contains("2026");
    }

    @Test
    void send_cancelled_messageContainsCancelled() {
        stubOkResponse("msg-id-4");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        AppointmentEvent event = scheduledEvent();
        event.setEventType(AppointmentEvent.EventType.CANCELLED);
        provider.send(event, credentials);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertThat(body.get("content").toString()).containsIgnoringCase("geannuleerd");
    }

    @Test
    void send_reminder24h_messageContainsHerinnering() {
        stubOkResponse("msg-id-5");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        AppointmentEvent event = scheduledEvent();
        event.setEventType(AppointmentEvent.EventType.REMINDER_24H);
        provider.send(event, credentials);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertThat(body.get("content").toString()).containsIgnoringCase("herinnering");
    }

    @Test
    void send_rateLimited_returnsFailure() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.TooManyRequests.create(
                        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        HttpHeaders.EMPTY, new byte[0], null));

        NotificationResult result = provider.send(scheduledEvent(), credentials);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("429");
    }

    @Test
    void providerName_isSwiftSend() {
        assertThat(provider.providerName()).isEqualTo("SwiftSend");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubOkResponse(String messageId) {
        ResponseEntity<Map> response = ResponseEntity.ok(Map.of("messageId", messageId));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(response);
    }

    private AppointmentEvent scheduledEvent() {
        AppointmentEvent e = new AppointmentEvent();
        e.setAppointmentUuid("appt-123");
        e.setPatientUuid("patient-456");
        e.setPatientPhone("+31612345678");
        e.setEventType(AppointmentEvent.EventType.SCHEDULED);
        e.setTimezone("Europe/Amsterdam");
        return e;
    }
}

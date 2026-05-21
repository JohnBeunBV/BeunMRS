package com.openmrs.notification.adapter.legacylink;

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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 6d — LegacyLinkProvider: SOAP envelope bevat correcte velden + XML-escaping,
 * Authorization: Basic header aanwezig, HTTP-fout levert failure op.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "null"})
class LegacyLinkProviderTest {

    @Mock private RestTemplate restTemplate;

    private LegacyLinkProvider provider;
    private ProviderCredentials credentials;

    @BeforeEach
    void setUp() {
        provider    = new LegacyLinkProvider(restTemplate, "http://fakecomworld:8080", "group-1");
        credentials = new ProviderCredentials("ll-user", "ll-password");
    }

    @Test
    void send_scheduled_returnsSuccess() {
        stubXmlResponse("<MessageReference>ref-001</MessageReference>");

        NotificationResult result = provider.send(scheduledEvent(), credentials);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProviderMessageId()).isEqualTo("ref-001");
    }

    @Test
    void send_includesBasicAuthHeader() {
        stubXmlResponse("<MessageReference>ref-002</MessageReference>");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        provider.send(scheduledEvent(), credentials);

        verify(restTemplate).exchange(
                contains("/LegacyLink/SendSms"),
                eq(HttpMethod.POST),
                captor.capture(),
                eq(String.class));

        String expectedBasic = "Basic " + Base64.getEncoder().encodeToString(
                "ll-user:ll-password".getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = captor.getValue().getHeaders();
        assertThat(headers.getFirst("Authorization")).isEqualTo(expectedBasic);
        assertThat(headers.getFirst("X-STUDENT-GROUP")).isEqualTo("group-1");
    }

    @Test
    void send_soapEnvelopeContainsPhoneNumber() {
        stubXmlResponse("<MessageReference>ref-003</MessageReference>");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        provider.send(scheduledEvent(), credentials);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        String body = (String) captor.getValue().getBody();
        assertThat(body).contains("<PhoneNumber>+31612345678</PhoneNumber>");
    }

    @Test
    void send_soapEnvelopeContainsAppointmentYear() {
        stubXmlResponse("<MessageReference>ref-004</MessageReference>");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        AppointmentEvent event = scheduledEvent();
        event.setAppointmentTime(Instant.parse("2026-05-22T10:00:00Z"));
        provider.send(event, credentials);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        String body = (String) captor.getValue().getBody();
        assertThat(body).contains("2026");
    }

    @Test
    void send_cancelled_messageContainsGeannuleerd() {
        stubXmlResponse("<MessageReference>ref-005</MessageReference>");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        AppointmentEvent event = scheduledEvent();
        event.setEventType(AppointmentEvent.EventType.CANCELLED);
        provider.send(event, credentials);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        String body = (String) captor.getValue().getBody();
        assertThat(body).containsIgnoringCase("geannuleerd");
    }

    @Test
    void send_reminder24h_messageContainsHerinnering() {
        stubXmlResponse("<MessageReference>ref-006</MessageReference>");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        AppointmentEvent event = scheduledEvent();
        event.setEventType(AppointmentEvent.EventType.REMINDER_24H);
        provider.send(event, credentials);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        String body = (String) captor.getValue().getBody();
        assertThat(body).containsIgnoringCase("herinnering");
    }

    @Test
    void send_xmlEscapesSpecialCharsInMessage() {
        stubXmlResponse("<MessageReference>ref-007</MessageReference>");
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);

        AppointmentEvent event = scheduledEvent();
        event.setPatientPhone("+31699999999");
        // Inject a name that would break XML if unescaped
        event.setLocationName("Kliniek & Zorg <Centrum>");
        provider.send(event, credentials);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        String body = (String) captor.getValue().getBody();
        // Special chars must be escaped
        assertThat(body).contains("&amp;");
        assertThat(body).contains("&lt;");
        assertThat(body).contains("&gt;");
        assertThat(body).doesNotContain(" & ");
    }

    @Test
    void send_httpError_returnsFailure() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                        HttpHeaders.EMPTY, new byte[0], null));

        NotificationResult result = provider.send(scheduledEvent(), credentials);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotNull();
    }

    @Test
    void providerName_isLegacyLink() {
        assertThat(provider.providerName()).isEqualTo("LegacyLink");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubXmlResponse(String xmlBody) {
        ResponseEntity<String> resp = ResponseEntity.ok(xmlBody);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(resp);
    }

    private AppointmentEvent scheduledEvent() {
        AppointmentEvent e = new AppointmentEvent();
        e.setAppointmentUuid("appt-ll-001");
        e.setPatientUuid("patient-ll-001");
        e.setPatientPhone("+31612345678");
        e.setEventType(AppointmentEvent.EventType.SCHEDULED);
        e.setAppointmentTime(Instant.parse("2026-05-22T10:00:00Z"));
        e.setTimezone("Europe/Amsterdam");
        return e;
    }
}

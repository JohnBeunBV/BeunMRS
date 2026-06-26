package com.openmrs.notification.service;

import com.openmrs.notification.config.RestTemplateFactory;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantContext;
import com.openmrs.notification.tenant.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NFR-5 — PersonContactService: verrijkt een AppointmentEvent met het telefoonnummer
 * uit OpenMRS (`Telephone Number`-attribuut), cachet per (tenant, patiënt) zodat een
 * tweede call geen extra OpenMRS-aanroep doet, en slaat verrijking veilig over wanneer
 * er geen tenant in context staat of de patiënt-UUID ontbreekt.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes", "null"})
class PersonContactServiceTest {

    @Mock private RestTemplateFactory restTemplateFactory;
    @Mock private TenantService       tenantService;
    @Mock private RestTemplate        rt;

    private PersonContactService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PersonContactService(restTemplateFactory, tenantService);
        TenantContext.set(tenant());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void enrichEvent_setsPhoneFromTelephoneAttribute() {
        stubOpenMrsPerson("0612345678");
        AppointmentEvent event = event("patient-1");

        service.enrichEvent(event);

        assertThat(event.getPatientPhone()).isEqualTo("0612345678");
    }

    @Test
    void enrichEvent_secondCallForSamePatient_usesCache() {
        stubOpenMrsPerson("0612345678");
        AppointmentEvent first  = event("patient-1");
        AppointmentEvent second = event("patient-1");

        service.enrichEvent(first);
        service.enrichEvent(second);

        // tweede keer uit cache → geen tweede OpenMRS-call
        verify(restTemplateFactory, times(1)).buildForTenant(any(), any());
        assertThat(second.getPatientPhone()).isEqualTo("0612345678");
    }

    @Test
    void enrichEvent_noTenantInContext_skipsEnrichment() {
        TenantContext.clear();
        AppointmentEvent event = event("patient-1");

        service.enrichEvent(event);

        assertThat(event.getPatientPhone()).isNull();
        verifyNoInteractions(restTemplateFactory);
    }

    @Test
    void enrichEvent_nullPatientUuid_doesNothing() {
        AppointmentEvent event = event(null);

        service.enrichEvent(event);

        assertThat(event.getPatientPhone()).isNull();
        verifyNoInteractions(restTemplateFactory);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubOpenMrsPerson(String phone) {
        when(tenantService.decryptOpenmrsPassword(any())).thenReturn("pw");
        when(restTemplateFactory.buildForTenant(any(), any())).thenReturn(rt);
        Map<String, Object> response = Map.of("attributes", List.of(
                Map.of("attributeType", Map.of("display", "Telephone Number"),
                       "value", phone)));
        when(rt.getForObject(anyString(), eq(Map.class))).thenReturn(response);
    }

    private AppointmentEvent event(String patientUuid) {
        AppointmentEvent e = new AppointmentEvent();
        e.setTenantId(tenantId);
        e.setPatientUuid(patientUuid);
        e.setAppointmentUuid("appt-pc-001");
        return e;
    }

    private Tenant tenant() {
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setSlug("test-tenant");
        t.setOpenmrsHost("http://gateway/openmrs");
        t.setOpenmrsUser("admin");
        return t;
    }
}

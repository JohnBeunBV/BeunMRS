package com.openmrs.notification.service;

import com.openmrs.notification.model.AppointmentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches patient contact details (phone + email) from the OpenMRS
 * Person REST API and enriches an AppointmentEvent in-place.
 *
 * Endpoint:
 *   GET /ws/rest/v1/person/{patientUuid}?v=full
 *   → response.attributes[] → attributeType.display + value
 *
 * Attribute display names (verified via GET /ws/rest/v1/personattributetype):
 *   - "Telephone Number"  →  patientPhone
 *   - "email"             →  patientEmail
 *
 * Caching
 * ───────
 * A simple in-memory cache (ConcurrentHashMap) is used to avoid calling
 * OpenMRS once per appointment during a bulk poll cycle. The same patient
 * often appears in multiple upcoming appointments.
 * Cache is bounded: evicted when it exceeds MAX_CACHE_SIZE entries.
 * Phone numbers and emails rarely change, so stale data is not a concern
 * for the typical lifetime of this service between restarts.
 */
@Service
public class PersonContactService {

    private static final Logger log = LoggerFactory.getLogger(PersonContactService.class);
    private static final int MAX_CACHE_SIZE = 500;

    private static final String ATTR_PHONE = "Telephone Number";
    private static final String ATTR_EMAIL  = "email";

    private final RestTemplate restTemplate;
    private final String       openmrsBaseUrl;

    // Simple bounded in-memory cache: patientUuid → PersonContacts
    private final Map<String, PersonContacts> cache = new ConcurrentHashMap<>();

    public PersonContactService(
            @Qualifier("openmrsRestTemplate") RestTemplate restTemplate,
            @Value("${openmrs.base-url:http://gateway/openmrs}") String openmrsBaseUrl) {
        this.restTemplate   = restTemplate;
        this.openmrsBaseUrl = openmrsBaseUrl;
    }

    /**
     * Looks up the phone number and email for the patient on the event
     * and sets them on the event object in-place.
     *
     * Failures are swallowed — a missing phone/email must never block
     * the appointment notification pipeline. Providers already handle
     * null phone/email gracefully.
     */
    public void enrichEvent(AppointmentEvent event) {
        if (event.getPatientUuid() == null) return;

        PersonContacts contacts = resolveContacts(event.getPatientUuid());
        if (contacts.phone() != null) event.setPatientPhone(contacts.phone());
        if (contacts.email() != null) event.setPatientEmail(contacts.email());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PersonContacts resolveContacts(String patientUuid) {
        // Evict cache when it grows too large (simple strategy)
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
        }
        return cache.computeIfAbsent(patientUuid, this::fetchFromOpenMrs);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private PersonContacts fetchFromOpenMrs(String patientUuid) {
        String url = openmrsBaseUrl + "/ws/rest/v1/person/" + patientUuid + "?v=full";
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.debug("[PersonContact] No response for patientUuid={}", patientUuid);
                return PersonContacts.EMPTY;
            }

            List<Map<String, Object>> attrs =
                    (List<Map<String, Object>>) resp.getBody().getOrDefault("attributes", List.of());

            String phone = null;
            String email = null;

            for (Map<String, Object> attr : attrs) {
                Map<String, Object> typeMap = (Map<String, Object>) attr.get("attributeType");
                if (typeMap == null) continue;

                String typeDisplay = (String) typeMap.get("display");
                Object value       = attr.get("value");
                if (value == null) continue;

                if (ATTR_PHONE.equals(typeDisplay)) {
                    phone = value.toString();
                } else if (ATTR_EMAIL.equals(typeDisplay)) {
                    email = value.toString();
                }
            }

            log.debug("[PersonContact] patientUuid={} phone={} email={}",
                    patientUuid, phone != null ? "found" : "null", email != null ? "found" : "null");
            return new PersonContacts(phone, email);

        } catch (Exception ex) {
            log.warn("[PersonContact] Failed to fetch contacts for patientUuid={}: {}",
                    patientUuid, ex.getMessage());
            return PersonContacts.EMPTY;
        }
    }

    // ── Value type ────────────────────────────────────────────────────────────

    /**
     * Immutable holder for phone + email.
     */
    public record PersonContacts(String phone, String email) {
        public static final PersonContacts EMPTY = new PersonContacts(null, null);
    }
}

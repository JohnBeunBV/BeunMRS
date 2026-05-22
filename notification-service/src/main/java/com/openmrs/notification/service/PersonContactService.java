package com.openmrs.notification.service;

import com.openmrs.notification.config.RestTemplateFactory;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantContext;
import com.openmrs.notification.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enriches an AppointmentEvent with patient phone number
 * by calling GET /ws/rest/v1/person/{uuid}?v=full.
 *
 * Cache is keyed by (tenantId, patientUuid) to avoid cross-tenant collisions.
 */
@Service
public class PersonContactService {

    private static final Logger log = LoggerFactory.getLogger(PersonContactService.class);
    private static final int    CACHE_MAX_SIZE = 1000;

    private final RestTemplateFactory restTemplateFactory;
    private final TenantService       tenantService;

    private final LinkedHashMap<String, CachedContact> cache =
            new LinkedHashMap<>(CACHE_MAX_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedContact> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            };

    public PersonContactService(RestTemplateFactory restTemplateFactory, TenantService tenantService) {
        this.restTemplateFactory = restTemplateFactory;
        this.tenantService       = tenantService;
    }

    public void enrichEvent(AppointmentEvent event) {
        if (event.getPatientUuid() == null) return;

        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            log.warn("[PersonContact] No tenant in context — skipping enrichment for patient={}",
                    event.getPatientUuid());
            return;
        }

        String cacheKey = tenant.getId() + ":" + event.getPatientUuid();
        CachedContact cached = cache.get(cacheKey);
        if (cached != null) {
            event.setPatientPhone(cached.phone());
            return;
        }

        try {
            String password = tenantService.decryptOpenmrsPassword(tenant);
            RestTemplate rt = restTemplateFactory.buildForTenant(tenant, password);
            String url      = tenant.getOpenmrsHost() + "/ws/rest/v1/person/" + event.getPatientUuid() + "?v=full";

            @SuppressWarnings("unchecked")
            Map<String, Object> response = rt.getForObject(url, Map.class);
            if (response == null) return;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attributes = (List<Map<String, Object>>) response.get("attributes");
            if (attributes == null) return;

            String phone = null;
            for (Map<String, Object> attr : attributes) {
                @SuppressWarnings("unchecked")
                Map<String, Object> attrType = (Map<String, Object>) attr.get("attributeType");
                if (attrType == null) continue;
                String display = (String) attrType.get("display");
                if ("Telephone Number".equalsIgnoreCase(display)) phone = (String) attr.get("value");
            }

            cache.put(cacheKey, new CachedContact(phone));
            event.setPatientPhone(phone);
            log.debug("[PersonContact] Enriched patient={}", event.getPatientUuid());

        } catch (Exception ex) {
            log.warn("[PersonContact] Could not fetch contact for patient={}: {}",
                    event.getPatientUuid(), ex.getMessage());
        }
    }

    private record CachedContact(String phone) {}
}

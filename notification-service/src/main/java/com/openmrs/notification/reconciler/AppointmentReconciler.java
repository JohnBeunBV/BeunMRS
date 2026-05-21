package com.openmrs.notification.reconciler;

import com.openmrs.notification.config.RestTemplateFactory;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.service.NotificationDispatcher;
import com.openmrs.notification.service.PersonContactService;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantContext;
import com.openmrs.notification.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backup reconciliator — catch-up mechanism for missed events.
 * Runs every 5 minutes for every active tenant.
 *
 * <p>Uses {@code POST /ws/rest/v1/appointment/search} with a sliding window
 * from the watermark to now+30 days — identical to the primary poller.
 * The old {@code GET ?lastUpdated=} endpoint was broken (returned HTTP 500).</p>
 */
@Component
public class AppointmentReconciler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReconciler.class);
    private static final String RESOURCE = "appointment";

    private final RestTemplateFactory  restTemplateFactory;
    private final JdbcTemplate         jdbc;
    private final NotificationDispatcher dispatcher;
    private final PersonContactService  personContactService;
    private final TenantService         tenantService;

    public AppointmentReconciler(
            RestTemplateFactory restTemplateFactory,
            JdbcTemplate jdbc,
            NotificationDispatcher dispatcher,
            PersonContactService personContactService,
            TenantService tenantService) {
        this.restTemplateFactory  = restTemplateFactory;
        this.jdbc                  = jdbc;
        this.dispatcher            = dispatcher;
        this.personContactService  = personContactService;
        this.tenantService         = tenantService;
    }

    @Scheduled(fixedDelayString = "${reconciler.poll.fixed-delay-ms:300000}",
               initialDelayString = "${reconciler.poll.initial-delay-ms:60000}")
    public void reconcile() {
        List<Tenant> tenants = tenantService.getActiveTenants();
        if (tenants.isEmpty()) {
            log.debug("Reconciler: no active tenants — skipping");
            return;
        }
        for (Tenant tenant : tenants) {
            TenantContext.set(tenant);
            try {
                reconcileForTenant(tenant);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void reconcileForTenant(Tenant tenant) {
        Instant since = readWatermark(tenant.getId());
        log.info("Reconciler running for tenant={} since {}", tenant.getSlug(), since);

        try {
            String password = tenantService.decryptOpenmrsPassword(tenant);
            RestTemplate rt = restTemplateFactory.buildForTenant(tenant, password);

            List<AppointmentEvent> events = fetchFromOpenMRS(rt, tenant, since);
            log.info("Reconciler found {} appointment(s) for tenant={}", events.size(), tenant.getSlug());

            for (AppointmentEvent event : events) {
                if (!alreadyProcessed(event, tenant.getId())) {
                    dispatcher.dispatch(event);
                }
            }
            advanceWatermark(tenant.getId(), Instant.now());

        } catch (Exception ex) {
            log.error("Reconciler poll failed for tenant={} — will retry at next interval",
                    tenant.getSlug(), ex);
        }
    }

    // ── Watermark ─────────────────────────────────────────────────────────────

    private Instant readWatermark(UUID tenantId) {
        try {
            String cursor = jdbc.queryForObject(
                    "SELECT last_cursor FROM sync_watermarks WHERE resource_type = ? AND tenant_id = ?",
                    String.class, RESOURCE, tenantId);
            return cursor != null ? Instant.parse(cursor) : Instant.now().minus(1, ChronoUnit.HOURS);
        } catch (Exception e) {
            return Instant.now().minus(1, ChronoUnit.HOURS);
        }
    }

    private void advanceWatermark(UUID tenantId, Instant now) {
        jdbc.update("""
            INSERT INTO sync_watermarks (resource_type, tenant_id, last_updated, last_cursor)
            VALUES (?, ?, now(), ?)
            ON CONFLICT (resource_type, tenant_id) DO UPDATE
            SET last_updated = now(), last_cursor = EXCLUDED.last_cursor
            """, RESOURCE, tenantId, now.toString());
    }

    // ── OpenMRS fetch ──────────────────────────────────────────────────────────

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final int RECONCILE_WINDOW_DAYS = 30;

    /**
     * Fetches appointments via {@code POST /ws/rest/v1/appointment/search}.
     *
     * Window: watermark (since) → now + 30 days.
     * The response is a JSON array (not wrapped in a "results" key).
     *
     * The old implementation used {@code GET ?lastUpdated=...} which always
     * returned HTTP 500 — that endpoint requires {@code ?uuid}, not a timestamp.
     */
    @SuppressWarnings("null") // HttpMethod.POST is provably non-null; false positive from Eclipse null-type checker
    private List<AppointmentEvent> fetchFromOpenMRS(RestTemplate rt, Tenant tenant, Instant since) {
        String  url       = tenant.getOpenmrsHost() + "/ws/rest/v1/appointment/search";
        Instant windowEnd = Instant.now().plus(RECONCILE_WINDOW_DAYS, ChronoUnit.DAYS);

        Map<String, String> body = Map.of(
                "startDate", ISO_FMT.format(since),
                "endDate",   ISO_FMT.format(windowEnd));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<List<Map<String, Object>>> response = rt.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<>() {});

            List<Map<String, Object>> rows = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || rows == null) {
                return List.of();
            }
            return rows.stream()
                    .map(r -> mapToEvent(r, tenant))
                    .toList();

        } catch (Exception ex) {
            log.warn("Reconciler POST /appointment/search failed for tenant={}: {}",
                    tenant.getSlug(), ex.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private AppointmentEvent mapToEvent(Map<String, Object> raw, Tenant tenant) {
        AppointmentEvent e = new AppointmentEvent();
        e.setTenantId(tenant.getId());
        e.setAppointmentUuid((String) raw.get("uuid"));
        e.setOccurredAt(Instant.now());
        e.setEventType(statusToEventType((String) raw.get("status")));

        Map<String, Object> patient = (Map<String, Object>) raw.get("patient");
        if (patient != null) {
            e.setPatientUuid((String) patient.get("uuid"));
            e.setPatientName((String) patient.get("name"));
        }
        Object start = raw.get("startDateTime");
        if (start instanceof Number) e.setAppointmentTime(Instant.ofEpochMilli(((Number) start).longValue()));

        Map<String, Object> location = (Map<String, Object>) raw.get("location");
        if (location != null) e.setLocationName((String) location.get("name"));

        personContactService.enrichEvent(e);
        return e;
    }

    private AppointmentEvent.EventType statusToEventType(String status) {
        if (status == null) return AppointmentEvent.EventType.SCHEDULED;
        return switch (status.toLowerCase()) {
            case "cancelled", "missed" -> AppointmentEvent.EventType.CANCELLED;
            case "scheduled"           -> AppointmentEvent.EventType.SCHEDULED;
            default                    -> AppointmentEvent.EventType.UPDATED;
        };
    }

    private boolean alreadyProcessed(AppointmentEvent event, UUID tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE tenant_id = ? AND event_type = ? AND payload::text LIKE ?",
                Integer.class, tenantId, event.getEventType().name(),
                "%" + event.getAppointmentUuid() + "%");
        return count != null && count > 0;
    }
}

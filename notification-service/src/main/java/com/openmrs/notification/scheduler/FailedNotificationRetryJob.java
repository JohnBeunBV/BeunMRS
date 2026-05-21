package com.openmrs.notification.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmrs.notification.adapter.NotificationProvider;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;
import com.openmrs.notification.service.PersonContactService;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantContext;
import com.openmrs.notification.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retries failed notification_log entries with exponential backoff.
 *
 * <h3>Why this is needed (NFR-6 + NFR-7)</h3>
 * <ul>
 *   <li>NFR-6: "Queueing en retry-mechanismen bij netwerkproblemen"</li>
 *   <li>NFR-7: "Downtime bij communicatieproviders dient te worden opgevangen
 *       door een fallback- of retrymechanisme"</li>
 * </ul>
 * Without this job a 429 or 503 from a provider causes a permanent silent loss.
 *
 * <h3>Retry schedule (exponential backoff)</h3>
 * <pre>
 *   Attempt 1 (retry_count 0→1): next_retry_at = now + 5 min
 *   Attempt 2 (retry_count 1→2): next_retry_at = now + 15 min
 *   Attempt 3 (retry_count 2→3): status = 'permanently_failed'
 * </pre>
 *
 * <h3>Contact-detail handling (NFR-5)</h3>
 * Phone and e-mail are stored masked in notification_log. On retry they are
 * re-fetched live from OpenMRS via {@link PersonContactService}. All other
 * non-PII fields (appointmentTime, locationName, comments, timezone) are
 * read directly from the stored JSONB payload — no extra OpenMRS call needed.
 */
@Component
public class FailedNotificationRetryJob {

    private static final Logger log = LoggerFactory.getLogger(FailedNotificationRetryJob.class);

    private static final int    BATCH_SIZE      = 10;
    private static final int    MAX_RETRIES     = 3;
    /** Backoff in minutes: after attempt 1 → 5 min, attempt 2 → 15 min. */
    private static final long[] BACKOFF_MINUTES = {5, 15};

    private final JdbcTemplate               jdbc;
    private final List<NotificationProvider> providers;
    private final TenantService              tenantService;
    private final PersonContactService       personContactService;
    private final ObjectMapper               objectMapper;

    public FailedNotificationRetryJob(JdbcTemplate jdbc,
                                      List<NotificationProvider> providers,
                                      TenantService tenantService,
                                      PersonContactService personContactService,
                                      ObjectMapper objectMapper) {
        this.jdbc                 = jdbc;
        this.providers            = providers;
        this.tenantService        = tenantService;
        this.personContactService = personContactService;
        this.objectMapper         = objectMapper;
    }

    // ── Scheduled entry point ─────────────────────────────────────────────────

    @Scheduled(fixedDelayString   = "${retry.failed.interval-ms:60000}",
               initialDelayString = "${retry.failed.initial-delay-ms:60000}")
    public void retryFailed() {

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, tenant_id, patient_uuid, channel, event_type,
                   payload::text AS payload, retry_count
              FROM notification_log
             WHERE status      = 'failed'
               AND retry_count < ?
               AND (next_retry_at IS NULL OR next_retry_at <= now())
             ORDER BY created_at
             LIMIT ?
            """, MAX_RETRIES, BATCH_SIZE);

        if (rows.isEmpty()) {
            log.debug("[RetryJob] Geen gefaalde notificaties voor herpoging");
            return;
        }

        log.info("[RetryJob] {} gefaalde notificatie(s) worden herproeeerd", rows.size());
        for (Map<String, Object> row : rows) {
            processRetry(row);
        }
    }

    // ── Core per-row logic ────────────────────────────────────────────────────

    private void processRetry(Map<String, Object> row) {
        String id           = row.get("id").toString();
        UUID   tenantId     = toUuid(row.get("tenant_id"));
        String providerName = (String) row.get("channel");
        String eventTypeStr = (String) row.get("event_type");
        String payloadJson  = (String) row.get("payload");
        int    retryCount   = ((Number) row.get("retry_count")).intValue();

        Tenant tenant = tenantService.findById(tenantId).orElse(null);
        if (tenant == null) {
            log.error("[RetryJob] Tenant id={} niet gevonden — permanent failed", tenantId);
            markPermanentlyFailed(id, "Tenant niet gevonden");
            return;
        }

        TenantContext.set(tenant);
        try {
            // Reconstruct the event from stored JSONB (without phone/email — those were masked)
            AppointmentEvent event = reconstructEvent(payloadJson, eventTypeStr, tenantId, tenant.getTimezone());
            if (event == null) {
                markPermanentlyFailed(id, "Payload kon niet worden geparsed");
                return;
            }

            // Re-fetch real phone/email from OpenMRS (NFR-5: not stored in plain text)
            personContactService.enrichEvent(event);

            // Resolve the exact provider that was originally used
            NotificationProvider provider = resolveProvider(providerName);
            if (provider == null) {
                log.error("[RetryJob] Provider '{}' niet beschikbaar voor log id={}", providerName, id);
                markPermanentlyFailed(id, "Provider '" + providerName + "' niet beschikbaar");
                return;
            }

            ProviderCredentials credentials = new ProviderCredentials(
                    tenantService.decryptProviderApiKey(tenant),
                    tenant.getProviderExtraEnc() != null
                            ? tenantService.decryptProviderExtra(tenant) : null
            );

            log.info("[RetryJob] Poging #{} — log id={} provider={} appointment={}",
                    retryCount + 1, id, providerName, event.getAppointmentUuid());

            NotificationResult result = provider.send(event, credentials);

            if (result.isSuccess()) {
                jdbc.update("""
                    UPDATE notification_log
                       SET status      = 'sent',
                           sent_at     = now(),
                           retry_count = ?
                     WHERE id = ?::uuid
                    """, retryCount + 1, id);
                log.info("[RetryJob] Herpoging geslaagd — log id={} na {} poging(en)",
                        id, retryCount + 1);
            } else {
                handleFailedAttempt(id, retryCount, result.getErrorMessage());
            }

        } catch (Exception ex) {
            log.error("[RetryJob] Onverwachte fout bij retry van log id={}: {}", id, ex.getMessage(), ex);
            handleFailedAttempt(id, retryCount, "Onverwachte fout: " + ex.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    // ── Failure handling ──────────────────────────────────────────────────────

    private void handleFailedAttempt(String id, int currentRetryCount, String errorMessage) {
        int newCount = currentRetryCount + 1;
        if (newCount >= MAX_RETRIES) {
            markPermanentlyFailed(id, errorMessage);
        } else {
            long backoffMin = BACKOFF_MINUTES[newCount - 1]; // 1→5 min, 2→15 min
            jdbc.update("""
                UPDATE notification_log
                   SET retry_count   = ?,
                       next_retry_at = now() + ? * interval '1 minute',
                       error_message = ?
                 WHERE id = ?::uuid
                """, newCount, backoffMin, errorMessage, id);
            log.warn("[RetryJob] Poging #{} mislukt — log id={}, volgende poging over {} min",
                    newCount, id, backoffMin);
        }
    }

    private void markPermanentlyFailed(String id, String reason) {
        jdbc.update("""
            UPDATE notification_log
               SET status        = 'permanently_failed',
                   retry_count   = ?,
                   error_message = ?
             WHERE id = ?::uuid
            """, MAX_RETRIES, reason, id);
        log.error("[RetryJob] Log id={} permanent mislukt na {} pogingen: {}", id, MAX_RETRIES, reason);
    }

    // ── Event reconstruction ──────────────────────────────────────────────────

    /**
     * Rebuilds an AppointmentEvent from the JSONB payload in notification_log.
     * Phone and e-mail are intentionally NOT restored here — they are re-fetched
     * from OpenMRS by {@link PersonContactService#enrichEvent} after this call.
     */
    private AppointmentEvent reconstructEvent(String payloadJson, String eventTypeStr,
                                              UUID tenantId, String timezone) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = objectMapper.readValue(payloadJson, Map.class);

            AppointmentEvent event = new AppointmentEvent();
            event.setTenantId(tenantId);
            event.setAppointmentUuid((String) p.get("appointmentUuid"));
            event.setPatientUuid((String) p.get("patientUuid"));
            event.setPatientName((String) p.get("patientName"));
            event.setLocationName((String) p.get("locationName"));
            event.setComments((String) p.get("comments"));
            event.setTimezone(timezone); // use current tenant timezone

            Object ts = p.get("appointmentTime");
            if (ts instanceof String s && !s.isBlank()) {
                event.setAppointmentTime(Instant.parse(s));
            }

            try {
                event.setEventType(AppointmentEvent.EventType.valueOf(eventTypeStr));
            } catch (IllegalArgumentException e) {
                event.setEventType(AppointmentEvent.EventType.SCHEDULED);
            }
            return event;
        } catch (Exception ex) {
            log.error("[RetryJob] Kon payload niet parsen: {}", ex.getMessage());
            return null;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private NotificationProvider resolveProvider(String providerName) {
        return providers.stream()
                .filter(p -> p.isEnabled() && p.providerName().equalsIgnoreCase(providerName))
                .findFirst()
                .orElse(null);
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID u)   return u;
        if (value instanceof String s) return UUID.fromString(s);
        throw new IllegalArgumentException("Onverwacht UUID type: " + value.getClass());
    }
}

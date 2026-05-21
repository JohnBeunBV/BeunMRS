package com.openmrs.notification.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enforces the two data-retention requirements from the assignment (NFR-10 and NFR-11).
 *
 * <h3>NFR-10 — 14-day PII deletion</h3>
 * Patient-related data (phone numbers, e-mail addresses embedded in payloads)
 * is deleted from all operational tables after 14 days.  The tables cleaned are:
 * <ul>
 *   <li>{@code notification_log}     — contains masked PII in the payload column</li>
 *   <li>{@code outbox_events}        — may contain contact data in the payload column</li>
 *   <li>{@code seen_appointments}    — contains appointment UUIDs (indirectly linkable)</li>
 *   <li>{@code scheduled_notifications} — JSONB payload with patient contact details</li>
 * </ul>
 * The tables {@code async_flow_commands} and {@code sync_watermarks} are NOT
 * cleaned here: they contain only command IDs / cursors with no PII.
 *
 * <h3>NFR-11 — 1-year meta-info retention</h3>
 * Before deleting {@code notification_log} rows a summary (no PII) is written to
 * {@code notification_audit_log}.  That audit log is itself purged after 1 year.
 * The audit log contains only: tenant_id, appointment_uuid, event_type, provider,
 * status, sent_at — sufficient to reconcile provider invoices.
 *
 * <p>Runs nightly at 02:00 to minimise load during business hours.</p>
 */
@Component
public class DataRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

    private final JdbcTemplate jdbc;

    public DataRetentionJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 0 2 * * *")   // every night at 02:00
    public void runRetention() {
        log.info("[Retention] Nachtelijke retentie gestart");
        try {
            archiveToAuditLog();
            deletePiiData();
            purgeOldAuditLog();
        } catch (Exception ex) {
            log.error("[Retention] Retentie mislukt — handmatige controle vereist", ex);
        }
        log.info("[Retention] Nachtelijke retentie klaar");
    }

    // ── Step 1: archive notification_log rows (14+ days old) to audit log ────

    /**
     * Copies the non-PII columns of notification_log rows older than 14 days
     * into notification_audit_log before they are deleted.
     *
     * INSERT … SELECT is idempotent via ON CONFLICT DO NOTHING on archived_from_id.
     */
    private void archiveToAuditLog() {
        int archived = jdbc.update("""
            INSERT INTO notification_audit_log
                (id, tenant_id, appointment_uuid, event_type, provider, status, sent_at,
                 archived_from_id, archived_at)
            SELECT
                gen_random_uuid(),
                tenant_id,
                payload->>'appointmentUuid',
                event_type,
                channel,
                status,
                sent_at,
                id,
                now()
            FROM notification_log
            WHERE created_at < now() - interval '14 days'
              AND NOT EXISTS (
                  SELECT 1 FROM notification_audit_log a WHERE a.archived_from_id = notification_log.id
              )
            """);
        if (archived > 0) {
            log.info("[Retention] {} notification_log rij(en) gearchiveerd naar audit log", archived);
        }
    }

    // ── Step 2: delete PII-containing rows older than 14 days ────────────────

    private void deletePiiData() {
        int logs = jdbc.update(
                "DELETE FROM notification_log WHERE created_at < now() - interval '14 days'");

        int outbox = jdbc.update(
                "DELETE FROM outbox_events WHERE created_at < now() - interval '14 days'");

        int seen = jdbc.update(
                "DELETE FROM seen_appointments WHERE queued_at < now() - interval '14 days'");

        int scheduled = jdbc.update(
                "DELETE FROM scheduled_notifications WHERE created_at < now() - interval '14 days'");

        log.info("[Retention] 14-dagenretentie: log={} outbox={} seen={} scheduled={}",
                logs, outbox, seen, scheduled);
    }

    // ── Step 3: purge audit log entries older than 1 year ────────────────────

    private void purgeOldAuditLog() {
        int purged = jdbc.update(
                "DELETE FROM notification_audit_log WHERE archived_at < now() - interval '1 year'");
        if (purged > 0) {
            log.info("[Retention] {} audit-log rij(en) ouder dan 1 jaar verwijderd", purged);
        }
    }
}

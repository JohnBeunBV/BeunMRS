package com.openmrs.notification.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

/**
 * NFR-10 / NFR-11 — DataRetentionJob: PII-houdende rijen worden na 14 dagen verwijderd
 * uit alle operationele tabellen, en de PII-vrije audit-log wordt na 1 jaar opgeschoond.
 * Vóór verwijdering wordt een PII-vrije samenvatting naar notification_audit_log gearchiveerd.
 *
 * <p>Valideert de retentie-statements die de matrix aan NFR-10/NFR-11 koppelt maar die
 * voorheen door geen enkele assert werden gedekt.</p>
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class DataRetentionJobTest {

    @Mock private JdbcTemplate jdbc;

    private DataRetentionJob job;

    @BeforeEach
    void setUp() {
        job = new DataRetentionJob(jdbc);
    }

    // ── NFR-11: archiveren (PII-vrij) vóór verwijdering ────────────────────────

    @Test
    void runRetention_archivesNonPiiToAuditLog_forRowsOlderThan14Days() {
        job.runRetention();

        verify(jdbc).update(argThat((String sql) ->
                sql.contains("INSERT INTO notification_audit_log")
                        && sql.contains("14 days")));
    }

    // ── NFR-10: 14-dagen verwijdering uit álle operationele tabellen ───────────

    @Test
    void runRetention_deletesPiiAfter14Days_fromAllOperationalTables() {
        job.runRetention();

        verify(jdbc).update(argThat((String sql) ->
                sql.contains("DELETE FROM notification_log") && sql.contains("14 days")));
        verify(jdbc).update(argThat((String sql) ->
                sql.contains("DELETE FROM outbox_events") && sql.contains("14 days")));
        verify(jdbc).update(argThat((String sql) ->
                sql.contains("DELETE FROM seen_appointments") && sql.contains("14 days")));
        verify(jdbc).update(argThat((String sql) ->
                sql.contains("DELETE FROM scheduled_notifications") && sql.contains("14 days")));
    }

    // ── NFR-11: audit-log na 1 jaar opschonen ──────────────────────────────────

    @Test
    void runRetention_purgesAuditLogAfterOneYear() {
        job.runRetention();

        verify(jdbc).update(argThat((String sql) ->
                sql.contains("DELETE FROM notification_audit_log") && sql.contains("1 year")));
    }
}

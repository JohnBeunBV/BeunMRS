-- 00_schema.sql — runs on first container start
-- Requires: docker compose down -v && docker compose up -d (clean volume)

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── Tenants (multi-tenant SaaS registry) ──────────────────────────────────
-- One row per hospital/organisation using this SaaS.
-- Credentials are stored AES-256-GCM encrypted at the application layer.
-- api_key_hash (SHA-256) allows O(1) lookup without decrypting every row.
CREATE TABLE IF NOT EXISTS tenants (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                 TEXT        UNIQUE NOT NULL,
    display_name         TEXT        NOT NULL,
    api_key_hash         TEXT        UNIQUE NOT NULL,
    api_key_enc          TEXT        NOT NULL,
    openmrs_host         TEXT        NOT NULL DEFAULT 'http://openmrs-gateway/openmrs',
    openmrs_user         TEXT        NOT NULL DEFAULT 'admin',
    openmrs_password_enc TEXT        NOT NULL,
    provider_name        TEXT        NOT NULL CHECK (provider_name IN ('SwiftSend','SecurePost','LegacyLink','AsyncFlow')),
    provider_api_key_enc TEXT        NOT NULL,
    provider_extra_enc   TEXT,
    timezone             TEXT        NOT NULL DEFAULT 'Europe/Amsterdam',
    active               BOOLEAN     NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Outbox (transactional at-least-once relay) ────────────────────────────
CREATE TABLE IF NOT EXISTS outbox_events (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id),
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    failed_at      TIMESTAMPTZ,
    retry_count    INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox_events (tenant_id, created_at)
    WHERE published_at IS NULL AND failed_at IS NULL;

-- ── Watermark (poller / reconciler cursor) ────────────────────────────────
-- PK is (resource_type, tenant_id) so each tenant has its own watermark.
CREATE TABLE IF NOT EXISTS sync_watermarks (
    resource_type  TEXT        NOT NULL,
    tenant_id      UUID        NOT NULL REFERENCES tenants(id),
    last_updated   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_cursor    TEXT,
    PRIMARY KEY (resource_type, tenant_id)
);

-- ── Seen appointments (duplicate guard for poller) ────────────────────────
CREATE TABLE IF NOT EXISTS seen_appointments (
    appointment_uuid TEXT        NOT NULL,
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    openmrs_status   TEXT,
    queued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified_at      TIMESTAMPTZ,
    PRIMARY KEY (appointment_uuid, tenant_id)
);
CREATE INDEX IF NOT EXISTS idx_seen_appointments_queued
    ON seen_appointments (tenant_id, queued_at DESC);

-- ── Notification log (audit trail) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS notification_log (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id),
    patient_uuid   TEXT        NOT NULL,
    channel        TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'pending',
    sent_at        TIMESTAMPTZ,
    error_message  TEXT,
    payload        JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notification_log_tenant
    ON notification_log (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notification_log_status
    ON notification_log (tenant_id, status, created_at DESC);

-- ── AsyncFlow pending commands ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS async_flow_commands (
    command_id        TEXT        PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenants(id),
    appointment_uuid  TEXT        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'pending',
    submitted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_asyncflow_pending
    ON async_flow_commands (tenant_id, submitted_at)
    WHERE status = 'pending';

-- ── Scheduled reminders (24h + 1h before appointment) ────────────────────────
CREATE TABLE IF NOT EXISTS scheduled_notifications (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    appointment_uuid TEXT        NOT NULL,
    type             TEXT        NOT NULL,
    send_at          TIMESTAMPTZ NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'pending',
    payload          JSONB       NOT NULL,
    sent_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sched_notif_pending_unique
    ON scheduled_notifications (tenant_id, appointment_uuid, type)
    WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_sched_notif_send_at
    ON scheduled_notifications (send_at)
    WHERE status = 'pending';

-- ── Notification audit log (no PII — 1-year meta-info for invoice reconciliation) ──
-- Created by DataRetentionJob before 14-day PII cleanup.
-- Contains only: appointment_uuid, event_type, provider, status, sent_at — no phone/email/name.
CREATE TABLE IF NOT EXISTS notification_audit_log (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    appointment_uuid TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    provider         TEXT        NOT NULL,
    status           TEXT        NOT NULL,
    sent_at          TIMESTAMPTZ,
    archived_from_id UUID        NOT NULL,
    archived_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_log_tenant
    ON notification_audit_log (tenant_id, sent_at DESC);

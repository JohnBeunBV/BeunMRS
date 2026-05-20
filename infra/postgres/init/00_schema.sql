-- 00_schema.sql — runs on first container start

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── Outbox (transactional at-least-once relay) ────────────────────────────
CREATE TABLE IF NOT EXISTS outbox_events (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
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
    ON outbox_events (created_at)
    WHERE published_at IS NULL AND failed_at IS NULL;

-- ── Watermark (poller / reconciler cursor) ────────────────────────────────
CREATE TABLE IF NOT EXISTS sync_watermarks (
    resource_type  TEXT        PRIMARY KEY,
    last_updated   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_cursor    TEXT
);

-- ── Seen appointments (duplicate guard for poller) ────────────────────────
-- Prevents double-notification when poll windows overlap or service restarts.
CREATE TABLE IF NOT EXISTS seen_appointments (
    appointment_uuid TEXT        PRIMARY KEY,
    openmrs_status   TEXT,
    queued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_seen_appointments_queued
    ON seen_appointments (queued_at DESC);

-- ── Notification log (audit trail) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS notification_log (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_uuid   TEXT        NOT NULL,
    channel        TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'pending',
    sent_at        TIMESTAMPTZ,
    error_message  TEXT,
    payload        JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notification_log_patient
    ON notification_log (patient_uuid, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notification_log_status
    ON notification_log (status, created_at DESC);

-- ── AsyncFlow pending commands ─────────────────────────────────────────────
-- Persists AsyncFlow command IDs so the status poller survives restarts.
CREATE TABLE IF NOT EXISTS async_flow_commands (
    command_id        TEXT        PRIMARY KEY,
    appointment_uuid  TEXT        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'pending',  -- pending | completed | failed
    submitted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_asyncflow_pending
    ON async_flow_commands (submitted_at)
    WHERE status = 'pending';

-- ── Scheduled reminders (24h + 1h before appointment) ────────────────────────
-- One row per reminder. payload JSONB stores the full AppointmentEvent snapshot
-- so the dispatch job can send without an extra OpenMRS call.
-- status: pending | sent | cancelled | failed
CREATE TABLE IF NOT EXISTS scheduled_notifications (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_uuid TEXT        NOT NULL,
    type             TEXT        NOT NULL,       -- '24h' | '1h'
    send_at          TIMESTAMPTZ NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'pending',
    payload          JSONB       NOT NULL,
    sent_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Prevent duplicate pending reminders for the same appointment+type
CREATE UNIQUE INDEX IF NOT EXISTS idx_sched_notif_pending_unique
    ON scheduled_notifications (appointment_uuid, type)
    WHERE status = 'pending';
-- Fast lookup for the dispatch job
CREATE INDEX IF NOT EXISTS idx_sched_notif_send_at
    ON scheduled_notifications (send_at)
    WHERE status = 'pending';

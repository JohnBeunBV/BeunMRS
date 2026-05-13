-- init/00_schema.sql
-- Runs once when the notification-db container is first created.
-- Add your actual migration tool (Flyway, Liquibase, Alembic, etc.)
-- on top of this baseline.

-- Extension: uuid generation
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── Outbox table ──────────────────────────────────────────────────────────
-- Used by the notification service for the transactional outbox pattern:
-- writes are committed atomically with business data, then a relay picks
-- them up and publishes to the broker. Guarantees at-least-once delivery
-- even if RabbitMQ or the network is temporarily unavailable.
CREATE TABLE IF NOT EXISTS outbox_events (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type TEXT        NOT NULL,          -- e.g. 'appointment'
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,          -- e.g. 'appointment.scheduled'
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,                   -- NULL = pending
    failed_at      TIMESTAMPTZ,
    retry_count    INT         NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox_events (created_at)
    WHERE published_at IS NULL AND failed_at IS NULL;

-- ── Watermark table ───────────────────────────────────────────────────────
-- Tracks the last-seen cursor per OpenMRS resource type so the polling
-- reconciler knows where to resume after a downtime gap.
CREATE TABLE IF NOT EXISTS sync_watermarks (
    resource_type  TEXT        PRIMARY KEY,       -- e.g. 'appointment'
    last_updated   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_cursor    TEXT                           -- opaque: UUID or ISO timestamp
);

-- ── Notification log ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notification_log (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_uuid   TEXT        NOT NULL,
    channel        TEXT        NOT NULL,          -- 'sms' | 'email' | 'push'
    event_type     TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'pending',
    sent_at        TIMESTAMPTZ,
    error_message  TEXT,
    payload        JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notification_log_patient
    ON notification_log (patient_uuid, created_at DESC);

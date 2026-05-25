package com.openmrs.notification.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Registers Prometheus gauges for queue depths that are not otherwise
 * observable from Spring Boot auto-metrics.
 *
 * Values are queried live on each Prometheus scrape (every ~15 s).
 */
@Component
public class MetricsGauges {

    private final MeterRegistry meterRegistry;
    private final JdbcTemplate  jdbc;

    public MetricsGauges(MeterRegistry meterRegistry, JdbcTemplate jdbc) {
        this.meterRegistry = meterRegistry;
        this.jdbc          = jdbc;
    }

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("scheduled_notifications_pending",
                        this, MetricsGauges::pendingReminders)
                .description("Aantal reminders in scheduled_notifications met status=pending")
                .register(meterRegistry);

        Gauge.builder("notification_log_failed",
                        this, MetricsGauges::failedNotifications)
                .description("Aantal notificaties in notification_log met status=failed (wachten op retry)")
                .register(meterRegistry);

        Gauge.builder("outbox_events_pending",
                        this, MetricsGauges::pendingOutboxEvents)
                .description("Aantal outbox_events die nog niet gepubliceerd zijn naar RabbitMQ")
                .register(meterRegistry);
    }

    private double pendingReminders() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_notifications WHERE status = 'pending'",
                Integer.class);
        return count != null ? count : 0;
    }

    private double failedNotifications() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE status = 'failed'",
                Integer.class);
        return count != null ? count : 0;
    }

    private double pendingOutboxEvents() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND failed_at IS NULL",
                Integer.class);
        return count != null ? count : 0;
    }
}

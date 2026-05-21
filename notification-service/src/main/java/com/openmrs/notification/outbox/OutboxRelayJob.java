package com.openmrs.notification.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantContext;
import com.openmrs.notification.tenant.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox relay — garanteert at-least-once delivery naar RabbitMQ.
 *
 * Draait elke 30 seconden, pikt maximaal 20 ongepubliceerde rijen op
 * en publiceert ze per tenant (TenantContext gezet per rij).
 *
 * Foutafhandeling: retry_count ophogen; na MAX_RETRIES → failed_at zetten.
 */
@Component
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    private static final String EXCHANGE    = "openmrs.events";
    private static final int    MAX_RETRIES = 5;
    private static final int    BATCH_SIZE  = 20;

    private final JdbcTemplate   jdbc;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper   objectMapper;
    private final TenantService  tenantService;

    public OutboxRelayJob(JdbcTemplate jdbc,
                          RabbitTemplate rabbitTemplate,
                          ObjectMapper objectMapper,
                          TenantService tenantService) {
        this.jdbc          = jdbc;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper  = objectMapper;
        this.tenantService = tenantService;
    }

    @Scheduled(fixedDelayString   = "${outbox.relay.fixed-delay-ms:30000}",
               initialDelayString = "${outbox.relay.initial-delay-ms:15000}")
    public void relay() {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, tenant_id, aggregate_id, event_type, payload::text AS payload, retry_count
                FROM outbox_events
                WHERE published_at IS NULL
                  AND failed_at IS NULL
                ORDER BY created_at
                LIMIT ?
                """, BATCH_SIZE);

        if (rows.isEmpty()) {
            log.debug("Outbox relay: geen openstaande events");
            return;
        }

        log.info("Outbox relay: {} openstaand(e) event(s) verwerken", rows.size());

        int published = 0;
        int failed    = 0;

        for (Map<String, Object> row : rows) {
            UUID   id          = toUuid(row.get("id"));
            UUID   tenantId    = toUuid(row.get("tenant_id"));
            String aggregateId = (String) row.get("aggregate_id");
            String eventType   = (String) row.get("event_type");
            int    retryCount  = ((Number) row.get("retry_count")).intValue();

            Tenant tenant = tenantService.findById(tenantId).orElse(null);
            if (tenant == null) {
                log.error("Relay: tenant niet gevonden id={} — entry permanent mislukt", tenantId);
                jdbc.update("UPDATE outbox_events SET failed_at = now() WHERE id = ?", id);
                failed++;
                continue;
            }

            TenantContext.set(tenant);
            try {
                AppointmentEvent event = buildEvent(aggregateId, eventType, (String) row.get("payload"));
                event.setTenantId(tenantId);

                String routingKey = resolveRoutingKey(eventType);
                rabbitTemplate.convertAndSend(EXCHANGE, routingKey, event);

                jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", id);
                log.info("Relay gepubliceerd: appointmentUuid={} eventType={} routingKey={}",
                        aggregateId, eventType, routingKey);
                published++;

            } catch (Exception ex) {
                int newRetryCount = retryCount + 1;
                if (newRetryCount >= MAX_RETRIES) {
                    jdbc.update("""
                        UPDATE outbox_events SET retry_count = ?, failed_at = now() WHERE id = ?
                        """, newRetryCount, id);
                    log.error("Outbox relay: entry permanent mislukt na {} pogingen — appointmentUuid={}",
                            MAX_RETRIES, aggregateId);
                } else {
                    jdbc.update("UPDATE outbox_events SET retry_count = ? WHERE id = ?", newRetryCount, id);
                    log.warn("Relay poging {} van {} mislukt voor appointmentUuid={}: {}",
                            newRetryCount, MAX_RETRIES, aggregateId, ex.getMessage());
                }
                failed++;
            } finally {
                TenantContext.clear();
            }
        }

        log.info("Outbox relay klaar — gepubliceerd: {}, mislukt: {}", published, failed);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AppointmentEvent buildEvent(String appointmentUuid,
                                        String eventType,
                                        String payloadJson) {
        AppointmentEvent event = new AppointmentEvent();
        event.setAppointmentUuid(appointmentUuid);
        event.setEventType(AppointmentEvent.EventType.valueOf(eventType));
        event.setOccurredAt(Instant.now());

        if (payloadJson != null) {
            try {
                Map<String, Object> payload = objectMapper.readValue(
                        payloadJson, new TypeReference<>() {});
                event.setPatientUuid((String) payload.get("patientUuid"));
            } catch (Exception e) {
                log.debug("Payload JSON kon niet worden gelezen voor appointmentUuid={}", appointmentUuid);
            }
        }
        return event;
    }

    private String resolveRoutingKey(String eventType) {
        if (eventType == null) return "appointment.scheduled";
        return switch (eventType.toUpperCase()) {
            case "CANCELLED" -> "appointment.cancelled";
            case "SCHEDULED" -> "appointment.scheduled";
            default          -> "appointment.updated";
        };
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID)   return (UUID) value;
        if (value instanceof String) return UUID.fromString((String) value);
        throw new IllegalArgumentException("Onverwacht UUID type: " + value.getClass());
    }
}

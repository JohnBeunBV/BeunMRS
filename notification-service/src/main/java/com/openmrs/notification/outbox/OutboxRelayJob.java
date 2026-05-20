package com.openmrs.notification.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmrs.notification.model.AppointmentEvent;
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
 * Waarom bestaat dit?
 * ──────────────────
 * De poller schrijft elk event EERST naar outbox_events in Postgres,
 * en publiceert daarna naar RabbitMQ. Als RabbitMQ op dat moment down
 * is (of de service crasht tussen schrijven en versturen), blijft de
 * rij staan met published_at = NULL.
 *
 * Deze job draait elke 30 seconden en pikt zulke 'vergeten' rijen op.
 * Zodra RabbitMQ weer bereikbaar is worden ze alsnog gepubliceerd.
 *
 * Foutafhandeling:
 * ────────────────
 * - Bij elke mislukte poging: retry_count + 1
 * - Na MAX_RETRIES pogingen: failed_at wordt gezet → rij wordt niet
 *   meer opgepakt maar blijft zichtbaar in de database voor inspectie.
 *
 * Data flow:
 *   outbox_events (published_at IS NULL)
 *       → lees aggregate_id + event_type + payload
 *       → bouw AppointmentEvent
 *       → rabbitTemplate.convertAndSend(exchange, routingKey, event)
 *       → UPDATE published_at = now()
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

    public OutboxRelayJob(JdbcTemplate jdbc,
                          RabbitTemplate rabbitTemplate,
                          ObjectMapper objectMapper) {
        this.jdbc           = jdbc;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper   = objectMapper;
    }

    /**
     * Draait elke 30 seconden. Pikt maximaal 20 ongepubliceerde rijen op
     * en probeert ze naar RabbitMQ te sturen.
     *
     * Instelbaar via: outbox.relay.fixed-delay-ms=30000
     */
    @Scheduled(fixedDelayString  = "${outbox.relay.fixed-delay-ms:30000}",
               initialDelayString = "${outbox.relay.initial-delay-ms:15000}")
    public void relay() {

        // ── Stap 1: Haal ongepubliceerde rijen op ─────────────────────────
        // published_at IS NULL → nog niet verzonden
        // failed_at IS NULL    → nog niet permanent opgegeven
        // LIMIT 20             → verwerk in kleine batches zodat één
        //                        trage RabbitMQ de hele loop niet blokkeert
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, aggregate_id, event_type, payload::text AS payload, retry_count
                FROM outbox_events
                WHERE published_at IS NULL
                  AND failed_at IS NULL
                ORDER BY created_at
                LIMIT ?
                """, BATCH_SIZE);

        if (rows.isEmpty()) {
            // Niets te doen — log alleen op DEBUG zodat Grafana niet volloopt
            log.debug("Outbox relay: geen openstaande events");
            return;
        }

        log.info("Outbox relay: {} openstaand(e) event(s) verwerken", rows.size());

        int published = 0;
        int failed    = 0;

        for (Map<String, Object> row : rows) {

            // UUID kan als java.util.UUID of String uit JDBC komen
            UUID   id           = toUuid(row.get("id"));
            String aggregateId  = (String) row.get("aggregate_id");  // = appointmentUuid
            String eventType    = (String) row.get("event_type");    // SCHEDULED / UPDATED / CANCELLED
            int    retryCount   = ((Number) row.get("retry_count")).intValue();

            try {
                // ── Stap 2: Bouw het AppointmentEvent terug op ─────────────
                AppointmentEvent event = buildEvent(aggregateId, eventType,
                                                    (String) row.get("payload"));

                // ── Stap 3: Bepaal de RabbitMQ routing key ─────────────────
                // appointment.scheduled → consumer stuurt bevestiging + plant reminders
                // appointment.cancelled → consumer annuleert geplande reminders
                // appointment.updated   → consumer herplant reminders
                String routingKey = resolveRoutingKey(eventType);

                // ── Stap 4: Publiceer naar RabbitMQ ────────────────────────
                rabbitTemplate.convertAndSend(EXCHANGE, routingKey, event);

                // ── Stap 5: Markeer als gepubliceerd ───────────────────────
                jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", id);

                log.info("Relay gepubliceerd: appointmentUuid={} eventType={} routingKey={}",
                        aggregateId, eventType, routingKey);
                published++;

            } catch (Exception ex) {

                // ── Foutafhandeling ────────────────────────────────────────
                int newRetryCount = retryCount + 1;

                if (newRetryCount >= MAX_RETRIES) {
                    // Permanent opgeven na MAX_RETRIES pogingen
                    jdbc.update("""
                            UPDATE outbox_events
                            SET retry_count = ?, failed_at = now()
                            WHERE id = ?
                            """, newRetryCount, id);
                    log.error("Outbox relay: entry permanent mislukt na {} pogingen — appointmentUuid={}",
                            MAX_RETRIES, aggregateId);
                } else {
                    // Tijdelijke fout — volgende run probeert opnieuw
                    jdbc.update("UPDATE outbox_events SET retry_count = ? WHERE id = ?",
                            newRetryCount, id);
                    log.warn("Relay poging {} van {} mislukt voor appointmentUuid={}: {}",
                            newRetryCount, MAX_RETRIES, aggregateId, ex.getMessage());
                }
                failed++;
            }
        }

        log.info("Outbox relay klaar — gepubliceerd: {}, mislukt: {}", published, failed);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Bouwt een AppointmentEvent op vanuit de opgeslagen outbox-rij.
     * De payload bevat de velden die bij het schrijven beschikbaar waren.
     * Velden die NULL zijn (patientPhone, patientEmail) worden later
     * ingevuld zodra Fase 2 (contactgegevens ophalen) klaar is.
     */
    private AppointmentEvent buildEvent(String appointmentUuid,
                                        String eventType,
                                        String payloadJson) {
        AppointmentEvent event = new AppointmentEvent();
        event.setAppointmentUuid(appointmentUuid);
        event.setEventType(AppointmentEvent.EventType.valueOf(eventType));
        event.setOccurredAt(Instant.now());

        // Lees extra velden uit het opgeslagen JSON-payload
        if (payloadJson != null) {
            try {
                Map<String, Object> payload = objectMapper.readValue(
                        payloadJson, new TypeReference<>() {});
                event.setPatientUuid((String) payload.get("patientUuid"));
                // patientName, appointmentTime, locationName worden opgeslagen
                // zodra Fase 2 de ophaallogica toevoegt
            } catch (Exception e) {
                log.debug("Payload JSON kon niet worden gelezen voor appointmentUuid={}",
                        appointmentUuid);
            }
        }

        return event;
    }

    /**
     * Vertaalt het interne EventType naar de RabbitMQ routing key.
     * Moet gelijk zijn aan de routing keys in OpenMrsAppointmentPoller.
     */
    private String resolveRoutingKey(String eventType) {
        if (eventType == null) return "appointment.scheduled";
        return switch (eventType.toUpperCase()) {
            case "CANCELLED" -> "appointment.cancelled";
            case "SCHEDULED" -> "appointment.scheduled";
            default          -> "appointment.updated";
        };
    }

    /** UUID kan als java.util.UUID of als String uit Postgres komen. */
    private UUID toUuid(Object value) {
        if (value instanceof UUID)   return (UUID) value;
        if (value instanceof String) return UUID.fromString((String) value);
        throw new IllegalArgumentException("Onverwacht UUID type: " + value.getClass());
    }
}

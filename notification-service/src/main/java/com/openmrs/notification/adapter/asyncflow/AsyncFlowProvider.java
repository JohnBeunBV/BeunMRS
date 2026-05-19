package com.openmrs.notification.adapter.asyncflow;

import com.openmrs.notification.adapter.NotificationProvider;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationChannel;
import com.openmrs.notification.model.NotificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * AsyncFlow — asynchronous REST API.
 *
 * Protocol:
 *  1. POST /api/asyncflow/commands  → receive commandId
 *  2. Return immediately (Result.ok with commandId — the "submitted" state)
 *  3. A separate @Scheduled poller checks status every 10s
 *  4. When status = "completed" → write final result to notification_log
 *  5. When status = "failed"    → write failure to notification_log
 *
 * The commandId is persisted to async_flow_commands table so we survive restarts.
 */
@Component
public class AsyncFlowProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(AsyncFlowProvider.class);

    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbc;
    private final String baseUrl;
    private final String apiKey;
    private final String studentGroup;

    public AsyncFlowProvider(
            RestTemplate restTemplate,
            JdbcTemplate jdbc,
            @Value("${fakecomworld.base-url:http://fakecomworld:8080}") String baseUrl,
            @Value("${provider.asyncflow.api-key:asyncflow-api-key}") String apiKey,
            @Value("${fakecomworld.student-group:group-1}") String studentGroup) {
        this.restTemplate = restTemplate;
        this.jdbc         = jdbc;
        this.baseUrl      = baseUrl;
        this.apiKey       = apiKey;
        this.studentGroup = studentGroup;
    }

    @Override public NotificationChannel channel() { return NotificationChannel.PUSH; }
    @Override public String providerName()          { return "AsyncFlow"; }

    /**
     * Step 1 — submit the command. Returns immediately with commandId.
     * Actual delivery is confirmed by the status poller below.
     */
    @Override
    public NotificationResult send(AppointmentEvent event) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY",       apiKey);
            headers.set("X-STUDENT-GROUP", studentGroup);

            Map<String, Object> body = Map.of(
                    "recipient", event.getPatientUuid() != null ? event.getPatientUuid() : "unknown",
                    "message",   buildMessage(event),
                    "reference", event.getAppointmentUuid()
            );

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/api/asyncflow/commands",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String commandId = String.valueOf(resp.getBody().getOrDefault("commandId", "unknown"));
                log.info("[AsyncFlow] Command submitted — appointment={} commandId={}", event.getAppointmentUuid(), commandId);
                persistCommand(commandId, event.getAppointmentUuid());
                // Return "pending" — final outcome tracked by poller
                return NotificationResult.ok("pending:" + commandId);
            }
            return NotificationResult.failure("HTTP " + resp.getStatusCode());

        } catch (Exception ex) {
            log.error("[AsyncFlow] Submit failed — appointment={}", event.getAppointmentUuid(), ex);
            return NotificationResult.failure(ex.getMessage());
        }
    }

    /**
     * Step 2 — poll for command outcomes every 10 seconds.
     * Processes all pending commands stored in async_flow_commands.
     */
    @Scheduled(fixedDelayString = "${asyncflow.poll.interval-ms:10000}")
    public void pollPendingCommands() {
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT command_id, appointment_uuid FROM async_flow_commands WHERE status = 'pending' LIMIT 50"
        );

        if (pending.isEmpty()) return;
        log.debug("[AsyncFlow] Polling {} pending command(s)", pending.size());

        for (Map<String, Object> row : pending) {
            String commandId      = (String) row.get("command_id");
            String appointmentUuid = (String) row.get("appointment_uuid");
            checkCommandStatus(commandId, appointmentUuid);
        }
    }

    @SuppressWarnings("unchecked")
    private void checkCommandStatus(String commandId, String appointmentUuid) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY",       apiKey);
            headers.set("X-STUDENT-GROUP", studentGroup);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/api/asyncflow/commands/" + commandId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return;

            String status = (String) resp.getBody().get("status");
            log.debug("[AsyncFlow] commandId={} status={}", commandId, status);

            switch (status.toLowerCase()) {
                case "completed" -> {
                    updateCommand(commandId, "completed");
                    updateNotificationLog(appointmentUuid, "sent");
                    log.info("[AsyncFlow] Completed — appointment={} commandId={}", appointmentUuid, commandId);
                }
                case "failed" -> {
                    String reason = (String) resp.getBody().getOrDefault("error", "unknown");
                    updateCommand(commandId, "failed");
                    updateNotificationLog(appointmentUuid, "failed");
                    log.warn("[AsyncFlow] Failed — appointment={} commandId={} reason={}", appointmentUuid, commandId, reason);
                }
                default -> log.debug("[AsyncFlow] Still processing — commandId={}", commandId);
            }

        } catch (Exception ex) {
            log.error("[AsyncFlow] Status check error — commandId={}", commandId, ex);
        }
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private void persistCommand(String commandId, String appointmentUuid) {
        jdbc.update("""
            INSERT INTO async_flow_commands (command_id, appointment_uuid, status, submitted_at)
            VALUES (?, ?, 'pending', now())
            ON CONFLICT (command_id) DO NOTHING
            """, commandId, appointmentUuid);
    }

    private void updateCommand(String commandId, String status) {
        jdbc.update("UPDATE async_flow_commands SET status = ?, resolved_at = now() WHERE command_id = ?",
                status, commandId);
    }

    private void updateNotificationLog(String appointmentUuid, String status) {
        jdbc.update("""
            UPDATE notification_log SET status = ?, sent_at = CASE WHEN ? = 'sent' THEN now() ELSE sent_at END
            WHERE payload::text LIKE ?
            """, status, status, "%" + appointmentUuid + "%");
    }

    private String buildMessage(AppointmentEvent event) {
        return switch (event.getEventType()) {
            case SCHEDULED -> String.format("Afspraak bevestigd op %s", event.getAppointmentTime());
            case UPDATED   -> String.format("Afspraak gewijzigd naar %s", event.getAppointmentTime());
            case CANCELLED -> "Uw afspraak is geannuleerd.";
        };
    }
}

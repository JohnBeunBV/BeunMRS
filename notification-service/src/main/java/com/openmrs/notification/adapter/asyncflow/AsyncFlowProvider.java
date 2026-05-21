package com.openmrs.notification.adapter.asyncflow;

import com.openmrs.notification.adapter.NotificationProvider;
import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationChannel;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;
import com.openmrs.notification.security.AesEncryptionService;
import com.openmrs.notification.tenant.Tenant;
import com.openmrs.notification.tenant.TenantService;
import com.openmrs.notification.util.MessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AsyncFlow — two-step asynchronous REST API.
 *
 * credentials.apiKey() = AsyncFlow API key (X-API-KEY header).
 *
 * The status poller looks up the tenant's API key per command via
 * async_flow_commands.tenant_id to avoid storing credentials redundantly.
 */
@Component
public class AsyncFlowProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(AsyncFlowProvider.class);

    private final RestTemplate    restTemplate;
    private final JdbcTemplate    jdbc;
    private final TenantService   tenantService;
    private final String          baseUrl;
    private final String          studentGroup;

    public AsyncFlowProvider(
            @Qualifier("providerRestTemplate") RestTemplate restTemplate,
            JdbcTemplate jdbc,
            TenantService tenantService,
            @Value("${fakecomworld.base-url:http://fakecomworld:8080}") String baseUrl,
            @Value("${fakecomworld.student-group:group-1}") String studentGroup) {
        this.restTemplate  = restTemplate;
        this.jdbc          = jdbc;
        this.tenantService = tenantService;
        this.baseUrl       = baseUrl;
        this.studentGroup  = studentGroup;
    }

    @Override public NotificationChannel channel() { return NotificationChannel.PUSH; }
    @Override public String providerName()          { return "AsyncFlow"; }

    @Override
    public NotificationResult send(AppointmentEvent event, ProviderCredentials credentials) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY",       credentials.apiKey());
            headers.set("X-STUDENT-GROUP", studentGroup);

            String destination = event.getPatientPhone() != null ? event.getPatientPhone()
                    : (event.getPatientUuid() != null ? event.getPatientUuid() : "unknown");

            Map<String, Object> body = Map.of(
                    "destination", destination,
                    "content",     buildMessage(event),
                    "priority",    "normal"
            );

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/asyncflow",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String commandId = String.valueOf(resp.getBody().getOrDefault("trackingId", "unknown"));
                log.info("[AsyncFlow] Command submitted — appointment={} commandId={}",
                        event.getAppointmentUuid(), commandId);
                persistCommand(commandId, event.getAppointmentUuid(), event.getTenantId());
                return NotificationResult.ok("pending:" + commandId);
            }
            return NotificationResult.failure("HTTP " + resp.getStatusCode());

        } catch (Exception ex) {
            log.error("[AsyncFlow] Submit failed — appointment={}", event.getAppointmentUuid(), ex);
            return NotificationResult.failure(ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${asyncflow.poll.interval-ms:10000}")
    public void pollPendingCommands() {
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT command_id, appointment_uuid, tenant_id FROM async_flow_commands WHERE status = 'pending' LIMIT 50"
        );
        if (pending.isEmpty()) return;
        log.debug("[AsyncFlow] Polling {} pending command(s)", pending.size());

        for (Map<String, Object> row : pending) {
            String commandId       = (String) row.get("command_id");
            String appointmentUuid = (String) row.get("appointment_uuid");
            UUID   tenantId        = (UUID)   row.get("tenant_id");

            String apiKey = resolveApiKey(tenantId);
            if (apiKey == null) {
                log.warn("[AsyncFlow] Could not resolve API key for tenant={} commandId={}", tenantId, commandId);
                continue;
            }
            checkCommandStatus(commandId, appointmentUuid, apiKey);
        }
    }

    private String resolveApiKey(UUID tenantId) {
        if (tenantId == null) return null;
        Optional<Tenant> tenant = tenantService.findById(tenantId);
        return tenant.map(tenantService::decryptProviderApiKey).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private void checkCommandStatus(String commandId, String appointmentUuid, String apiKey) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY",       apiKey);
            headers.set("X-STUDENT-GROUP", studentGroup);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/asyncflow/" + commandId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return;

            String status = (String) resp.getBody().get("status");
            switch (status.toLowerCase()) {
                case "completed" -> {
                    updateCommand(commandId, "completed");
                    updateNotificationLog(appointmentUuid, "sent");
                    log.info("[AsyncFlow] Completed — appointment={} commandId={}", appointmentUuid, commandId);
                }
                case "failed" -> {
                    updateCommand(commandId, "failed");
                    updateNotificationLog(appointmentUuid, "failed");
                    log.warn("[AsyncFlow] Failed — appointment={} commandId={}", appointmentUuid, commandId);
                }
                default -> log.debug("[AsyncFlow] Still processing — commandId={}", commandId);
            }
        } catch (Exception ex) {
            log.error("[AsyncFlow] Status check error — commandId={}", commandId, ex);
        }
    }

    private void persistCommand(String commandId, String appointmentUuid, UUID tenantId) {
        jdbc.update("""
            INSERT INTO async_flow_commands (command_id, tenant_id, appointment_uuid, status, submitted_at)
            VALUES (?, ?, ?, 'pending', now())
            ON CONFLICT (command_id) DO NOTHING
            """, commandId, tenantId, appointmentUuid);
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
        String time     = MessageHelper.formatTime(event.getAppointmentTime(), event.getTimezone());
        String loc      = MessageHelper.locationSuffix(event.getLocationName());
        String comments = MessageHelper.commentsSuffix(event.getComments());
        return switch (event.getEventType()) {
            case SCHEDULED    -> String.format("Afspraak bevestigd op %s%s.%s", time, loc, comments);
            case UPDATED      -> String.format("Afspraak gewijzigd naar %s%s.%s", time, loc, comments);
            case CANCELLED    -> String.format("Uw afspraak op %s is geannuleerd.", time);
            case REMINDER_24H -> String.format("Herinnering: uw afspraak is morgen om %s%s.%s", time, loc, comments);
            case REMINDER_1H  -> String.format("Herinnering: uw afspraak is over een uur (%s)%s.%s", time, loc, comments);
        };
    }
}

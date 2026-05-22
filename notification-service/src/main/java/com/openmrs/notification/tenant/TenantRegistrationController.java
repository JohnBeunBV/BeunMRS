package com.openmrs.notification.tenant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.util.Set;

/**
 * Self-service tenant onboarding.
 * POST /api/register — no authentication required.
 *
 * Returns the generated API key once in plaintext — store it immediately.
 */
@RestController
@RequestMapping("/api/register")
public class TenantRegistrationController {

    private static final Set<String> VALID_PROVIDERS =
            Set.of("SwiftSend", "SecurePost", "LegacyLink", "AsyncFlow");

    private final TenantService tenantService;

    public TenantRegistrationController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    public ResponseEntity<?> register(@RequestBody TenantRegistrationRequest req) {
        if (req.slug() == null || !req.slug().matches("[a-z0-9\\-]+")) {
            return ResponseEntity.badRequest().body(error("slug must be lowercase alphanumeric with dashes"));
        }
        if (req.displayName() == null || req.displayName().isBlank()) {
            return ResponseEntity.badRequest().body(error("displayName is required"));
        }
        if (req.openmrsHost() == null || req.openmrsHost().isBlank()) {
            return ResponseEntity.badRequest().body(error("openmrsHost is required"));
        }
        if (req.openmrsUser() == null || req.openmrsUser().isBlank()) {
            return ResponseEntity.badRequest().body(error("openmrsUser is required"));
        }
        if (req.openmrsPassword() == null || req.openmrsPassword().isBlank()) {
            return ResponseEntity.badRequest().body(error("openmrsPassword is required"));
        }
        if (!VALID_PROVIDERS.contains(req.providerName())) {
            return ResponseEntity.badRequest().body(error("providerName must be one of: " + VALID_PROVIDERS));
        }
        if (req.providerApiKey() == null || req.providerApiKey().isBlank()) {
            return ResponseEntity.badRequest().body(error("providerApiKey is required"));
        }
        if (req.timezone() != null && !req.timezone().isBlank()) {
            try {
                ZoneId.of(req.timezone());
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(error(
                        "timezone is not a valid IANA timezone identifier (e.g. 'Europe/Amsterdam')"));
            }
        }

        try {
            TenantRegistrationResponse result = tenantService.register(req);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("unique")) {
                return ResponseEntity.badRequest().body(error("slug already in use"));
            }
            // Don't expose exception details to client (NFR security)
            return ResponseEntity.internalServerError().body(error("Registration failed"));
        }
    }

    private java.util.Map<String, String> error(String message) {
        return java.util.Map.of("error", message);
    }
}

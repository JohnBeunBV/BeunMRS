package com.openmrs.notification.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operational management endpoints for the SaaS platform.
 * Protected by SAAS_ADMIN_KEY master key (X-Admin-Key header).
 */
@RestController
@RequestMapping("/api/admin/tenants")
public class TenantAdminController {

    private final TenantService tenantService;
    private final String        adminKey;

    public TenantAdminController(TenantService tenantService,
                                 @Value("${saas.admin-key:admin-secret}") String adminKey) {
        this.tenantService = tenantService;
        this.adminKey      = adminKey;
    }

    @GetMapping
    public ResponseEntity<?> listTenants(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isValidAdminKey(key)) return unauthorized();
        List<Tenant> tenants = tenantService.getActiveTenants();
        List<Map<String, Object>> view = tenants.stream().map(t -> Map.<String, Object>of(
                "id",           t.getId(),
                "slug",         t.getSlug(),
                "displayName",  t.getDisplayName(),
                "providerName", t.getProviderName(),
                "openmrsHost",  t.getOpenmrsHost(),
                "active",       t.isActive(),
                "createdAt",    t.getCreatedAt()
        )).toList();
        return ResponseEntity.ok(view);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivate(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isValidAdminKey(key)) return unauthorized();
        tenantService.deactivate(id);
        return ResponseEntity.ok(Map.of("message", "Tenant deactivated"));
    }

    /**
     * Constant-time vergelijking van de admin-sleutel om timing-aanvallen te
     * voorkomen ({@link MessageDigest#isEqual} stopt niet vroegtijdig bij de
     * eerste afwijkende byte, zoals {@code String.equals} wel doet).
     */
    private boolean isValidAdminKey(String key) {
        if (key == null) return false;
        return MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "X-Admin-Key required"));
    }
}

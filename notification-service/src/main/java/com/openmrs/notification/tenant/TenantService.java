package com.openmrs.notification.tenant;

import com.openmrs.notification.security.AesEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final JdbcTemplate          jdbc;
    private final AesEncryptionService  aes;

    public TenantService(JdbcTemplate jdbc, AesEncryptionService aes) {
        this.jdbc = jdbc;
        this.aes  = aes;
    }

    public List<Tenant> getActiveTenants() {
        return jdbc.queryForList("SELECT * FROM tenants WHERE active = true")
                .stream().map(this::mapRow).toList();
    }

    public Optional<Tenant> findByApiKey(String rawApiKey) {
        String hash = sha256(rawApiKey);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM tenants WHERE api_key_hash = ? AND active = true", hash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    public Optional<Tenant> findById(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM tenants WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    /**
     * Register a new tenant. Returns the tenant with the raw (plaintext) API key
     * populated in apiKeyEnc — this is the only time the key is returned in plaintext.
     */
    public TenantRegistrationResponse register(TenantRegistrationRequest req) {
        String rawApiKey    = "saas-" + UUID.randomUUID();
        String apiKeyHash   = sha256(rawApiKey);
        String apiKeyEnc    = aes.encrypt(rawApiKey);
        String passwordEnc  = aes.encrypt(req.openmrsPassword());
        String provKeyEnc   = aes.encrypt(req.providerApiKey());
        String provExtraEnc = req.providerExtra() != null ? aes.encrypt(req.providerExtra()) : null;
        String timezone     = (req.timezone() != null && !req.timezone().isBlank())
                              ? req.timezone() : "Europe/Amsterdam";
        UUID   id           = UUID.randomUUID();

        jdbc.update("""
            INSERT INTO tenants (id, slug, display_name, api_key_hash, api_key_enc,
                openmrs_host, openmrs_user, openmrs_password_enc,
                provider_name, provider_api_key_enc, provider_extra_enc, timezone, active, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, now())
            """,
            id, req.slug(), req.displayName(), apiKeyHash, apiKeyEnc,
            req.openmrsHost(), req.openmrsUser(), passwordEnc,
            req.providerName(), provKeyEnc, provExtraEnc, timezone
        );

        log.info("Tenant registered: slug={} provider={}", req.slug(), req.providerName());
        return new TenantRegistrationResponse(id, req.slug(), req.displayName(), rawApiKey);
    }

    public void deactivate(UUID id) {
        jdbc.update("UPDATE tenants SET active = false WHERE id = ?", id);
        log.info("Tenant deactivated: id={}", id);
    }

    // ── Credential decryption ──────────────────────────────────────────────────

    public String decryptOpenmrsPassword(Tenant t) {
        return aes.decrypt(t.getOpenmrsPasswordEnc());
    }

    public String decryptProviderApiKey(Tenant t) {
        return aes.decrypt(t.getProviderApiKeyEnc());
    }

    public String decryptProviderExtra(Tenant t) {
        return aes.decrypt(t.getProviderExtraEnc());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Tenant mapRow(Map<String, Object> row) {
        Tenant t = new Tenant();
        t.setId((UUID) row.get("id"));
        t.setSlug((String) row.get("slug"));
        t.setDisplayName((String) row.get("display_name"));
        t.setApiKeyHash((String) row.get("api_key_hash"));
        t.setApiKeyEnc((String) row.get("api_key_enc"));
        t.setOpenmrsHost((String) row.get("openmrs_host"));
        t.setOpenmrsUser((String) row.get("openmrs_user"));
        t.setOpenmrsPasswordEnc((String) row.get("openmrs_password_enc"));
        t.setProviderName((String) row.get("provider_name"));
        t.setProviderApiKeyEnc((String) row.get("provider_api_key_enc"));
        t.setProviderExtraEnc((String) row.get("provider_extra_enc"));
        String tz = (String) row.get("timezone");
        t.setTimezone(tz != null ? tz : "Europe/Amsterdam");
        t.setActive(Boolean.TRUE.equals(row.get("active")));
        Timestamp ts = (Timestamp) row.get("created_at");
        if (ts != null) t.setCreatedAt(ts.toInstant());
        return t;
    }

    public String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}

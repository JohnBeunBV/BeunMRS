package com.openmrs.notification.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Security tests for {@link TenantApiKeyFilter} — proves authentication and tenant
 * isolation work at the HTTP boundary.
 *
 * Covers NFR-2c (security best practices) and NFR-1 (multi-tenant isolation) at
 * the framework-integration level: a request carrying tenant A's key must never
 * resolve to tenant B, and missing/invalid keys are rejected before the handler runs.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class TenantApiKeyFilterTest {

    @Mock private TenantService tenantService;

    private MockMvc mockMvc;
    private TenantCapturingController capturing;

    @BeforeEach
    void setUp() {
        capturing = new TenantCapturingController();
        mockMvc = MockMvcBuilders.standaloneSetup(capturing)
                .addFilters(new TenantApiKeyFilter(tenantService))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void protectedEndpoint_missingHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/appointments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("X-API-Key")));
        verifyNoInteractions(tenantService);
    }

    @Test
    void protectedEndpoint_blankHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/appointments").header("X-API-Key", "   "))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tenantService);
    }

    @Test
    void protectedEndpoint_invalidKey_returns401() throws Exception {
        when(tenantService.findByApiKey("forged-key")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/appointments").header("X-API-Key", "forged-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Invalid")));
    }

    @Test
    void protectedEndpoint_validKey_setsTenantContextAndProceeds() throws Exception {
        Tenant tenantA = tenant("amc");
        when(tenantService.findByApiKey("key-amc")).thenReturn(Optional.of(tenantA));

        mockMvc.perform(get("/api/appointments").header("X-API-Key", "key-amc"))
                .andExpect(status().isOk());

        assertThat(capturing.capturedTenant).isSameAs(tenantA);
    }

    @Test
    void crossTenant_keyAResolvesToTenantA_neverToTenantB() throws Exception {
        Tenant tenantA = tenant("amc");
        Tenant tenantB = tenant("emc");
        when(tenantService.findByApiKey("key-amc")).thenReturn(Optional.of(tenantA));
        when(tenantService.findByApiKey("key-emc")).thenReturn(Optional.of(tenantB));

        // Request with tenant A's key must resolve to tenant A
        mockMvc.perform(get("/api/appointments").header("X-API-Key", "key-amc"))
                .andExpect(status().isOk());
        assertThat(capturing.capturedTenant).isSameAs(tenantA);

        // Same connection re-used with tenant B's key must resolve to tenant B,
        // never bleed from the previous request's ThreadLocal
        mockMvc.perform(get("/api/appointments").header("X-API-Key", "key-emc"))
                .andExpect(status().isOk());
        assertThat(capturing.capturedTenant).isSameAs(tenantB);

        verify(tenantService).findByApiKey("key-amc");
        verify(tenantService).findByApiKey("key-emc");
    }

    @Test
    void tenantContext_isClearedAfterRequest_evenOnHandlerException() throws Exception {
        Tenant tenantA = tenant("amc");
        when(tenantService.findByApiKey("key-amc")).thenReturn(Optional.of(tenantA));

        // Handler throws — MockMvc surfaces it via 500, but the filter MUST still clear context
        try {
            mockMvc.perform(get("/api/boom").header("X-API-Key", "key-amc"));
        } catch (Exception ignored) {
            // standalone MockMvc rethrows handler exceptions
        }

        assertThat(TenantContext.get())
                .as("ThreadLocal must be cleared even when downstream handler throws")
                .isNull();
    }

    @Test
    void publicEndpoint_register_bypassesFilter() throws Exception {
        mockMvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        // Filter must not call tenantService for /api/register
        verify(tenantService, never()).findByApiKey(any());
    }

    @Test
    void publicEndpoint_admin_bypassesFilter() throws Exception {
        mockMvc.perform(get("/api/admin/tenants"));

        // /api/admin/** uses its own X-Admin-Key check, not X-API-Key
        verify(tenantService, never()).findByApiKey(any());
    }

    @Test
    void publicEndpoint_nonApiPath_bypassesFilter() throws Exception {
        mockMvc.perform(get("/actuator/health"));

        verify(tenantService, never()).findByApiKey(any());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Tenant tenant(String slug) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setSlug(slug);
        t.setDisplayName(slug);
        t.setProviderName("SwiftSend");
        t.setTimezone("Europe/Amsterdam");
        return t;
    }

    /**
     * Catch-all controller that records whichever Tenant the filter put in
     * TenantContext at the moment the handler ran.
     */
    @RestController
    static class TenantCapturingController {
        Tenant capturedTenant;

        @GetMapping({"/api/appointments", "/api/register", "/api/admin/tenants", "/actuator/health"})
        public String capture(HttpServletRequest req) {
            capturedTenant = TenantContext.get();
            return "ok";
        }

        @GetMapping("/api/boom")
        public String boom() {
            // Trigger an exception while TenantContext is set, to verify the
            // filter's finally-block clears the ThreadLocal.
            throw new RuntimeException("simulated handler failure");
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/register")
        public String registerStub() {
            capturedTenant = TenantContext.get();
            return "ok";
        }
    }
}

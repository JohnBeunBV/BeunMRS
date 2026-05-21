package com.openmrs.notification.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 6i — TenantRegistrationController: validaties leveren 400 op, geldige registratie levert
 * 200 + apiKey op.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class TenantRegistrationControllerTest {

    @Mock private TenantService tenantService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TenantRegistrationController controller = new TenantRegistrationController(tenantService);
        // Use an ObjectMapper that can deserialise Java records (relies on -parameters compiler flag
        // set by spring-boot-maven-plugin; also add JavaTimeModule for safety).
        ObjectMapper mapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void register_validRequest_returns200WithApiKey() throws Exception {
        when(tenantService.register(any())).thenReturn(
                new TenantRegistrationResponse(UUID.randomUUID(), "amc", "Amsterdam UMC", "saas-key-abc"));

        mockMvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value("saas-key-abc"))
                .andExpect(jsonPath("$.slug").value("amc"));
    }

    @Test
    void register_invalidSlug_uppercase_returns400() throws Exception {
        mockMvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("AMC", "Amsterdam UMC", "SwiftSend", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("slug")));
    }

    @Test
    void register_invalidSlug_withSpaces_returns400() throws Exception {
        mockMvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("my hospital", "Test", "SwiftSend", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_blankDisplayName_returns400() throws Exception {
        mockMvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("valid-slug", "", "SwiftSend", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("displayName")));
    }

    @Test
    void register_invalidProviderName_returns400() throws Exception {
        mockMvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("valid-slug", "Test Hospital", "TelegramBot", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("providerName")));
    }

    @Test
    void register_invalidTimezone_returns400() throws Exception {
        mockMvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("valid-slug", "Test Hospital", "SwiftSend", "Nergens/Onbekend")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("timezone")));
    }

    @Test
    void register_validTimezone_accepted() throws Exception {
        when(tenantService.register(any())).thenReturn(
                new TenantRegistrationResponse(UUID.randomUUID(), "valid-slug", "Test Hospital", "saas-key"));

        mockMvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("valid-slug", "Test Hospital", "SecurePost", "Asia/Singapore")))
                .andExpect(status().isOk());
    }

    @Test
    void register_duplicateSlug_returns400() throws Exception {
        when(tenantService.register(any()))
                .thenThrow(new RuntimeException("duplicate key value violates unique constraint"));

        mockMvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("slug already in use")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String validBody() {
        return body("amc", "Amsterdam UMC", "SwiftSend", null);
    }

    private String body(String slug, String displayName, String providerName, String timezone) {
        String tz = timezone != null ? "\"" + timezone + "\"" : "null";
        return """
            {
              "slug":            "%s",
              "displayName":     "%s",
              "openmrsHost":     "http://openmrs:80",
              "openmrsUser":     "admin",
              "openmrsPassword": "Admin1234",
              "providerName":    "%s",
              "providerApiKey":  "sk-test-key",
              "timezone":        %s
            }
            """.formatted(slug, displayName, providerName, tz);
    }
}

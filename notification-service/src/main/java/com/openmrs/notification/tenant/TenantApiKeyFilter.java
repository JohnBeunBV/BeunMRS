package com.openmrs.notification.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates every /api/** request (except /api/register and /api/admin/**)
 * by reading the X-API-Key header and resolving the tenant.
 *
 * Sets TenantContext for the duration of the request; clears it in finally.
 */
@Component
public class TenantApiKeyFilter extends OncePerRequestFilter {

    private final TenantService tenantService;

    public TenantApiKeyFilter(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Paths that don't require tenant authentication
        if (!path.startsWith("/api/")
                || path.startsWith("/api/register")
                || path.startsWith("/api/admin/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            reject(response, "X-API-Key header required");
            return;
        }

        Optional<Tenant> tenant = tenantService.findByApiKey(apiKey);
        if (tenant.isEmpty()) {
            reject(response, "Invalid or inactive API key");
            return;
        }

        TenantContext.set(tenant.get());
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        // Use ObjectMapper to safely serialize JSON (prevents injection)
        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(Map.of("error", message)));
    }
}

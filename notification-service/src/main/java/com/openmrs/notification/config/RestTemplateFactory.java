package com.openmrs.notification.config;

import com.openmrs.notification.tenant.Tenant;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Base64;

/**
 * Creates per-tenant RestTemplates for OpenMRS REST API calls.
 *
 * Each tenant has its own OpenMRS host and credentials, so we cannot
 * use a singleton bean. A new RestTemplate is built per poll cycle
 * (lightweight — just sets default headers on a shared client).
 */
@Component
public class RestTemplateFactory {

    private final RestTemplateBuilder builder;

    public RestTemplateFactory(RestTemplateBuilder builder) {
        this.builder = builder;
    }

    public RestTemplate buildForTenant(Tenant tenant, String decryptedPassword) {
        String credentials = Base64.getEncoder().encodeToString(
                (tenant.getOpenmrsUser() + ":" + decryptedPassword).getBytes());

        return builder
                .defaultHeader("Authorization", "Basic " + credentials)
                .defaultHeader("Accept", "application/json")
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }
}

package com.openmrs.notification.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Base64;

@Configuration
public class AppConfig {

    /**
     * Jackson-based AMQP message converter — deserializes JSON from RabbitMQ
     * into AppointmentEvent POJOs automatically.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RestTemplate for OpenMRS REST API calls.
     *
     * Has a default Authorization: Basic header so that every call to
     * /ws/rest/v1/... is automatically authenticated. Used by:
     *   - OpenMrsAppointmentPoller
     *   - AppointmentReconciler
     *   - PersonContactService
     */
    @Bean
    @Qualifier("openmrsRestTemplate")
    public RestTemplate openmrsRestTemplate(
            RestTemplateBuilder builder,
            @Value("${openmrs.api.username:admin}") String openmrsUser,
            @Value("${openmrs.api.password:Admin1234}") String openmrsPassword) {

        String credentials = Base64.getEncoder()
                .encodeToString((openmrsUser + ":" + openmrsPassword).getBytes());

        return builder
                .defaultHeader("Authorization", "Basic " + credentials)
                .defaultHeader("Accept", "application/json")
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * RestTemplate for external notification provider calls (FakeComWorld).
     *
     * No default headers — each provider sets its own authentication
     * (X-API-KEY, Bearer JWT, SOAP Basic Auth) per request. This ensures
     * that OpenMRS credentials never leak to external services. Used by:
     *   - SwiftSendProvider
     *   - SecurePostProvider
     *   - LegacyLinkProvider
     *   - AsyncFlowProvider
     */
    @Bean
    @Qualifier("providerRestTemplate")
    public RestTemplate providerRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }
}

package com.openmrs.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class AppConfig {

    /**
     * Jackson-based AMQP message converter — deserializes JSON from RabbitMQ
     * into AppointmentEvent POJOs automatically.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * RestTemplate for external notification provider calls (FakeComWorld).
     *
     * No default headers — each provider sets its own authentication
     * (X-API-KEY, Bearer JWT, SOAP Basic Auth) per request. OpenMRS calls
     * use per-tenant RestTemplates built by RestTemplateFactory instead.
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

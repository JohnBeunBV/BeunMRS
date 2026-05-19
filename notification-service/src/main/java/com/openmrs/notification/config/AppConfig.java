package com.openmrs.notification.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
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
     * RestTemplate used for both OpenMRS REST polling and the mock messaging API.
     * Basic auth headers for OpenMRS are applied via the builder.
     */
    @Bean
    public RestTemplate restTemplate(
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
}

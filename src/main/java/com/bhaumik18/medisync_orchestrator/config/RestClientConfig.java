package com.bhaumik18.medisync_orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import io.micrometer.observation.ObservationRegistry;

@Configuration
public class RestClientConfig {
    
    @Value("${services.identity.url}")
    private String identityServiceUrl;
    
    @Value("${services.core.url}")
    private String coreServiceUrl;
    
    // We bypass the missing Builder error by creating it ourselves and manually injecting the Zipkin registry.
    @Bean
    public RestClient identityServiceClient(ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .baseUrl(identityServiceUrl)
                .observationRegistry(observationRegistry) // This is what passes the Trace IDs
                .build();
    }
    
    @Bean
    public RestClient coreServiceClient(ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .baseUrl(coreServiceUrl)
                .observationRegistry(observationRegistry) // This is what passes the Trace IDs
                .build();
    }
}
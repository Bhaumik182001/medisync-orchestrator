package com.bhaumik18.medisync_orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {
	
	@Value("${services.identity.url}")
	private String identityServiceUrl;
	
	@Value("${services.core.url}")
	private String coreServiceUrl;
	
	@Bean
	public RestClient identityServiceClient() {
		return RestClient.builder().baseUrl(identityServiceUrl).build();
	}
	
	@Bean
	public RestClient coreServiceClient() {
		return RestClient.builder().baseUrl(coreServiceUrl).build();
	}
}

package com.bhaumik18.medisync_orchestrator.config; // Update this package name to match your service

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**") // Apply to all endpoints
                        .allowedOrigins("http://localhost:5173", 
                                "https://medisync-frontend-vert.vercel.app") // Allow your Vite frontend
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // Allow standard methods + preflight
                        .allowedHeaders("*") 
                        .allowCredentials(true);
            }
        };
    }
}
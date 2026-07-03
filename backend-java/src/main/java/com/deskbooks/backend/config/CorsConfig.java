package com.deskbooks.backend.config;

import java.util.Arrays;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsOrigins())
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    private String[] corsOrigins() {
        String configured = System.getenv("PFA_CORS_ORIGINS");
        if (configured != null && !configured.isBlank()) {
            return Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(origin -> !origin.isEmpty())
                    .toArray(String[]::new);
        }

        String frontendPort = System.getenv().getOrDefault("FRONTEND_PORT", "5173");
        return new String[] {
                "http://localhost:" + frontendPort,
                "http://127.0.0.1:" + frontendPort
        };
    }
}

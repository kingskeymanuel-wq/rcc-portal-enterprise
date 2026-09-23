package com.ecobank.rccportal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Équivalent de la config `cors` côté Node (src/app.js) : liste blanche vide
 * par défaut = same-origin uniquement (recommandé en prod derrière un seul host).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(org.springframework.core.env.Environment env) {
        String raw = env.getProperty("rcc.cors.allowed-origins", "");
        this.allowedOrigins = raw.isBlank() ? new String[0] : raw.split(",");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.length == 0) return; // same-origin uniquement, rien à ouvrir

        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true);
    }
}
package com.ecobank.rccportal.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Lit rcc.auth.* depuis application.yml — jamais de secret en dur dans le code. */
@Getter @Setter
@Configuration
@ConfigurationProperties(prefix = "rcc.auth")
public class JwtProperties {
    private String jwtSecret;
    private int jwtAccessExpiresInMinutes = 15;
    private int jwtRefreshExpiresInDays = 7;
    private int bcryptRounds = 12;
    private int maxFailedAttempts = 5;
    private int lockoutDurationMinutes = 15;
    private int twoFactorCodeTtlMinutes = 10;
}

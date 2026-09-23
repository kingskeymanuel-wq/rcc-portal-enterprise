package com.ecobank.rccportal.security;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.util.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("rcc.auth.jwt-secret doit contenir au moins 32 octets.");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String generateAccessToken(User user, String role, String service) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("role", role != null ? role : "USER")
                .claim("service", service)
                // Compatibilité temporaire avec l'ancien frontend qui attend encore la propriété "team".
                .claim("team", service)
                .claim("name", user.getName())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getJwtAccessExpiresInMinutes(), ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public GeneratedRefreshToken generateRefreshToken(User user) {
        UUID jti = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getJwtRefreshExpiresInDays(), ChronoUnit.DAYS);
        String token = Jwts.builder()
                .subject(user.getUsername())
                .id(jti.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new GeneratedRefreshToken(token, jti, expiresAt);
    }

    /**
     * Refuse proprement un token absent/vide (ApiException 401) plutôt que de laisser
     * remonter une NullPointerException — utile car cette méthode est appelée à la fois
     * depuis AuthService.refresh() (qui vérifie déjà en amont) ET depuis
     * JwtAuthenticationFilter (sur chaque requête, y compris sans cookie posé), qui ne
     * garantit pas la même vérification préalable. Un token corrompu/signature invalide
     * est aussi refusé proprement (JwtException capturée) plutôt que de remonter brut.
     */
    public Claims parseClaims(String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.unauthorized("Missing or empty token.");
        }
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            throw ApiException.unauthorized("Invalid or expired token.");
        }
    }

    public record GeneratedRefreshToken(String token, UUID jti, Instant expiresAt) {
    }
}
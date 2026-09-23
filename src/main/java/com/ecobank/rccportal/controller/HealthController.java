package com.ecobank.rccportal.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.Map;

/**
 * /api/health est whitelisté publiquement dans SecurityConfig (utilisé par les
 * health checks de déploiement — load balancer, orchestrateur) : il doit
 * exister indépendamment de tout démarrage complet du contexte applicatif.
 * Vérifie aussi que la base SQL Server répond, pas seulement que le process JVM tourne.
 */
@Slf4j
@RestController
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        try (var connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) {
                throw new IllegalStateException("Database connection is not valid.");
            }
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (Exception ex) {
            log.error("Health check failed: database is unreachable", ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN"));
        }
    }
}

package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.service.AdAuthClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * TEMPORAIRE — pour valider l'intégration AD réelle uniquement.
 * À SUPPRIMER une fois le test terminé.
 */
@Slf4j
@RestController
@RequestMapping("/api/test/ad")
public class TestAdController {

    private final AdAuthClient adAuthClient;

    public TestAdController(AdAuthClient adAuthClient) {
        this.adAuthClient = adAuthClient;
    }

    @PostMapping("/login")
    public AdAuthClient.Result testLogin(
            @RequestParam String username,
            @RequestParam String password) {

        log.info("[TEST AD] tentative pour username={}", username);
        AdAuthClient.Result result = adAuthClient.authenticate(username, password);
        log.info("[TEST AD] résultat status={} message={}", result.status(), result.message());
        return result;
    }
}
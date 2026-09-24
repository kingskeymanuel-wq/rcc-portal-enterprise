package com.ecobank.rccportal.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ⚠️ Mode de contournement de l'authentification réelle (AD + MFA Ecobank), pour
 * des comptes de test locaux qui n'existent pas dans l'AD réel. À n'activer QUE
 * sur un poste de développement — voir AuthService.initiateLogin/completeLogin,
 * où ce mode court-circuite entièrement AdAuthClient et MfaService pour les
 * comptes listés ici.
 *
 * NE JAMAIS activer en production (rcc.auth.test-bypass.enabled doit rester à
 * false). Chaque connexion en mode bypass écrit un WARN explicite dans les logs.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rcc.auth.test-bypass")
public class TestBypassProperties {

    private boolean enabled = false;

    /** Code OTP accepté pour les comptes de test — n'importe quoi d'autre est refusé. */
    private String otpCode = "000000";

    private List<Account> accounts = new ArrayList<>();

    @Getter
    @Setter
    public static class Account {
        private String username;
        private String password;
        private String name;
        private String email;
        private String role;
        private String service;
        /** Pour tester la redirection Outbound (voir TeamClassifier / UserService.teamStatus). */
        private String activity;
        /** Uniquement pour role=TEAM_LEADER — voir User.ledTeam / TeamClassifier.Team. */
        private String ledTeam;
        /** Uniquement pour les agents d'agence : code agence (ex. K27) rattaché d'office (Portail Agence). */
        private String agencyCode;
    }
}
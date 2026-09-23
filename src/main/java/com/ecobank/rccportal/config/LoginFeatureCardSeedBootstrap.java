package com.ecobank.rccportal.config;

import com.ecobank.rccportal.model.LoginFeatureCard;
import com.ecobank.rccportal.repository.LoginFeatureCardRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crée les 4 cartes "fonctionnalité" par défaut de la page de connexion,
 * identiques à celles codées en dur avant que ce système ne devienne
 * éditable depuis Administration > Apparence — n'ajoute rien si la table
 * contient déjà au moins une carte (QA/Admin a pris la main).
 */
@Slf4j
@Component
@Order(23) // après KnowledgeBaseImportBootstrap (Order 22)
public class LoginFeatureCardSeedBootstrap implements CommandLineRunner {

    private final LoginFeatureCardRepository repository;

    public LoginFeatureCardSeedBootstrap(LoginFeatureCardRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        try {
            if (repository.count() > 0) return;
            repository.save(LoginFeatureCard.builder().icon("bi-shield-lock-fill").title("Sécurité Enterprise").subtitle("Active Directory • MFA • JWT").sortOrder(1).active(true).build());
            repository.save(LoginFeatureCard.builder().icon("bi-book-half").title("Knowledge Base").subtitle("Procédures • FAQ • RCC Scripts").sortOrder(2).active(true).build());
            repository.save(LoginFeatureCard.builder().icon("bi-mortarboard-fill").title("Formation Continue").subtitle("Parcours • Quiz • Badges").sortOrder(3).active(true).build());
            repository.save(LoginFeatureCard.builder().icon("bi-bar-chart-fill").title("Dashboard Enterprise").subtitle("KPIs • QA • Reporting").sortOrder(4).active(true).build());
            log.warn("⚠ [LOGIN CARDS] 4 cartes par défaut créées sur la page de connexion.");
        } catch (Exception e) {
            // Table pas encore créée (migration pas exécutée) — pas bloquant, réessaiera au prochain démarrage.
        }
    }
}

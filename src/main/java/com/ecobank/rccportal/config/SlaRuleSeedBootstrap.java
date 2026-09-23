package com.ecobank.rccportal.config;

import com.ecobank.rccportal.model.SlaRule;
import com.ecobank.rccportal.repository.SlaRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seed initial de la table SlaRules (délais de traitement communiqués au client pour
 * chaque type de demande/réclamation RCC) — extrait du référentiel SLA officiel.
 * N'ajoute rien si la table contient déjà des lignes (Administration a pris la main
 * et peut éditer/compléter la liste depuis l'écran dédié).
 *
 * Cette table est utilisée par RAF (voir RalphSearchService.buildSlaContext) pour ne
 * jamais inventer un délai, et sert de base au futur Supervisor AI pour mesurer le
 * respect des SLA RCC.
 */
@Slf4j
@Component
@Order(24) // après LoginFeatureCardSeedBootstrap (Order 23)
public class SlaRuleSeedBootstrap implements CommandLineRunner {

    private final SlaRuleRepository repository;

    public SlaRuleSeedBootstrap(SlaRuleRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        try {
            if (repository.count() > 0) return;

            List<SlaRule> rules = List.of(
                    rule("Envoi de relevé de compte client ECI", "Documents bancaires", "N1",
                            24, "24 heures", "Digital", "HAUTE", false,
                            "Le RCC extrait le relevé et le transmet au service Digital pour envoi au client.", 1),
                    rule("Envoi de relevé de compte autre filiale Ecobank", "Documents bancaires", "N2",
                            48, "48 heures", "Niveau 2", "HAUTE", false,
                            "Escalade au niveau 2 (hors ECI).", 2),
                    rule("Transmission de documents administratifs (attestation, référence bancaire, etc.)",
                            "Documents bancaires", "N1", 120, "5 jours ouvrés", "Agence / Back-office",
                            "MOYENNE", false, null, 3),

                    rule("Rachat de police d'assurance", "Assurance", "N2",
                            120, "5 jours ouvrés", "Assurance", "MOYENNE", false,
                            "Délai annoncé au client pour le suivi de son dossier de rachat.", 4),
                    rule("Contestation de prélèvement assurance", "Assurance", "N2",
                            120, "5 jours ouvrés", "Assurance", "MOYENNE", false,
                            "SLA décompté après création de l'incident Niveau 2.", 5),

                    rule("Réclamation retrait GAB avec carte (retrait non obtenu)", "Cartes bancaires et GAB", "N2",
                            720, "30 jours ouvrés", "Résolution", "BASSE", false,
                            "Cas : compte débité, client n'a pas reçu l'argent. SLA décompté après ouverture du dossier.", 6),
                    rule("Carte débitée sans dispense GAB", "Cartes bancaires et GAB", "N1/N2",
                            48, "2 jours ouvrés", "Résolution", "HAUTE", true,
                            "Le distributeur ne remet pas l'argent malgré le débit. SLA applicable uniquement si aucune reverse automatique n'intervient.", 7),
                    rule("Réclamation retrait partiel GAB", "Cartes bancaires et GAB", "N2",
                            120, "5 jours ouvrés", "Résolution", "MOYENNE", false,
                            "Ex. : client demande 100 000 FCFA, la machine ne remet que 50 000 FCFA.", 8),
                    rule("Disponibilité automatique d'une reverse DAB", "Cartes bancaires et GAB", "N1",
                            24, "24 heures", "Système / Monétique", "HAUTE", true,
                            "Reverse automatique pour les incidents très récents — pas d'ouverture de dossier si elle intervient.", 9),

                    rule("Réclamation Mobile Money", "Mobile Money", "N2",
                            120, "5 jours ouvrés", "Mobile Money", "MOYENNE", false,
                            "Applicable si la reverse automatique (72h) n'a pas eu lieu.", 10),
                    rule("Disponibilité automatique Mobile Money", "Mobile Money", "N1",
                            72, "72 heures", "Système / Mobile Money", "HAUTE", true,
                            "Reverse automatique attendue en premier lieu avant tout dossier manuel.", 11),

                    rule("Contestation transaction CashXpress", "Cartes prépayées CashXpress", "N2",
                            1080, "45 jours ouvrés", "CashXpress", "BASSE", false,
                            "SLA le plus long du référentiel — contestation d'opération / litige sur transaction.", 12),

                    rule("Étude d'une demande de crédit", "Crédits", "N1",
                            72, "72 heures ouvrées (recontact)", "Comité crédit", "MOYENNE", false,
                            "Le client doit être recontacté sous 72 heures ouvrées après transmission du dossier — délai de recontact, pas de clôture du dossier.", 13)
            );

            repository.saveAll(rules);
            log.warn("⚠ [SLA RCC] {} règles SLA par défaut créées (référentiel officiel RCC).", rules.size());
        } catch (Exception e) {
            // Table pas encore créée (migration pas exécutée) — pas bloquant, réessaiera au prochain démarrage.
        }
    }

    private static SlaRule rule(String motif, String category, String level, int slaHours, String slaLabel,
                                 String destinationService, String priority, boolean autoEscalation,
                                 String notes, int sortOrder) {
        return SlaRule.builder()
                .motif(motif)
                .category(category)
                .level(level)
                .slaHours(slaHours)
                .slaLabel(slaLabel)
                .destinationService(destinationService)
                .priority(priority)
                .autoEscalation(autoEscalation)
                .notes(notes)
                .isActive(true)
                .sortOrder(sortOrder)
                .build();
    }
}

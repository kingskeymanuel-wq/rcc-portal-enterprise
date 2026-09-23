package com.ecobank.rccportal.config;

import com.ecobank.rccportal.model.RccPole;
import com.ecobank.rccportal.model.SlaRule;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.RccPoleRepository;
import com.ecobank.rccportal.repository.SlaRuleRepository;
import com.ecobank.rccportal.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seed initial des fiches pôle RCC (organigramme cliquable — voir RccPoleController)
 * à partir des fiches pôle officielles diffusées en interne (Inbound, Outbound,
 * Résolution, Opérations, Business/Agency Banking, Agences). N'ajoute rien si la table
 * contient déjà des pôles (QA/Admin a pris la main depuis l'écran dédié).
 *
 * Le manager de chaque pôle est résolu par e-mail SI un compte correspondant existe déjà
 * en base (voir DevAccountsBootstrap pour les comptes de démo) ; sinon le lien reste vide
 * et un QA/Admin l'associe depuis l'écran — aucun compte n'est inventé ici.
 */
@Slf4j
@Component
@Order(25) // après SlaRuleSeedBootstrap (Order 24) — indépendant, même palier de seed "référentiels"
public class RccPoleSeedBootstrap implements CommandLineRunner {

    private final RccPoleRepository poleRepository;
    private final SlaRuleRepository slaRuleRepository;
    private final UserRepository userRepository;

    public RccPoleSeedBootstrap(RccPoleRepository poleRepository, SlaRuleRepository slaRuleRepository,
                                 UserRepository userRepository) {
        this.poleRepository = poleRepository;
        this.slaRuleRepository = slaRuleRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        try {
            if (poleRepository.count() > 0) return;

            int sort = 1;
            seedInbound(sort++);
            seedOutbound(sort++);
            seedResolution(sort++);
            seedOperations(sort++);
            seedBusiness(sort++);
            seedAgences(sort);

            log.warn("⚠ [SEED] Fiches pôle RCC créées (Inbound, Outbound, Résolution, Opérations, Business, Agences).");
        } catch (Exception e) {
            log.error("Échec du seed des fiches pôle RCC : {}", e.getMessage(), e);
        }
    }

    private User byEmail(String email) {
        if (email == null) return null;
        return userRepository.findByEmailIgnoreCase(email).orElse(null);
    }

    private RccPole pole(String name, String managerEmail, String contactPhone, String teamContactLabel,
                          String contactEmail, String whoWeAre, String whatWeDo, int sortOrder) {
        RccPole p = RccPole.builder()
                .name(name)
                .manager(byEmail(managerEmail))
                .contactPhone(contactPhone)
                .teamContactLabel(teamContactLabel)
                .contactEmail(contactEmail)
                .whoWeAre(whoWeAre)
                .whatWeDo(whatWeDo)
                .isActive(true)
                .sortOrder(sortOrder)
                .build();
        return poleRepository.save(p);
    }

    private void activity(RccPole pole, String category, String motif, String slaLabel, int sortOrder) {
        SlaRule rule = SlaRule.builder()
                .pole(pole)
                .category(category)
                .motif(motif)
                .slaLabel(slaLabel)
                .slaHours(parseHours(slaLabel))
                .autoEscalation(false)
                .isActive(true)
                .sortOrder(sortOrder)
                .build();
        slaRuleRepository.save(rule);
    }

    /** Conversion best-effort du libellé affiché vers un nombre d'heures (tri/calcul RAF) —
     *  seuls les libellés à UNE valeur numérique unique sont convertis (ex. "24H", "5 jours",
     *  "10MN") ; tout libellé composite ("24 à 48H", "30 à 45 jours") ou non numérique
     *  ("Selon le volume de la base", "Non défini") reste à 0 plutôt que de produire un
     *  résultat absurde en concaténant plusieurs nombres trouvés dans le texte. */
    private int parseHours(String label) {
        if (label == null) return 0;
        String v = label.trim().toUpperCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([0-9]+)\\s*").matcher(v);
        if (!m.find()) {
            return v.contains("INSTANTAN") ? 0 : 0;
        }
        // Un deuxième nombre ailleurs dans le libellé (ex. "24 à 48H") signale un intervalle —
        // on ne choisit pas arbitrairement une borne, on laisse à 0 (voir Javadoc ci-dessus).
        if (v.substring(m.end()).matches(".*[0-9].*")) {
            return 0;
        }
        int value = Integer.parseInt(m.group(1));
        if (v.contains("MN") || v.contains("MIN")) return Math.max(1, value / 60);
        if (v.contains("SEMAINE")) return value * 24 * 7;
        if (v.contains("JOUR")) return value * 24;
        if (v.contains("H")) return value;
        return 0;
    }

    // ---------- PÔLE INBOUND ----------
    private void seedInbound(int sortOrder) {
        RccPole p = pole("Pôle Inbound", "lagboton@ecobank.com", "0544921165",
                "Numéro vert : 9955 — Numéro long : 2721210021", "assist@ecobank.com",
                "L'équipe de prise en charge des interactions entrantes pour les particuliers.",
                "Appels entrants, Mails, Réseaux sociaux et Rafiki tchats.", sortOrder);
        int i = 1;
        activity(p, "Mobile app", "Création et activation", "24H", i++);
        activity(p, "Mobile app", "Reset code", "24H", i++);
        activity(p, "Mobile app", "Ajout de compte", "24H", i++);
        activity(p, "Mobile app", "Assistance", "Instantanée", i++);
        activity(p, "Ecobank Online", "Création et activation", "24H", i++);
        activity(p, "Ecobank Online", "Reset code", "24H", i++);
        activity(p, "Ecobank Online", "Assistance", "Instantanée", i++);
        activity(p, "Carte", "Augmentation de limite", "3H", i++);
        activity(p, "Carte", "Reset code pin", "10MN", i++);
        activity(p, "Carte", "Blocage de carte", "10MN", i++);
        activity(p, "Carte prépayée", "Augmentation de limite", "3H", i++);
        activity(p, "Carte prépayée", "Reset code pin", "10MN", i++);
        activity(p, "Carte prépayée", "Blocage de carte", "10MN", i++);
        activity(p, "Bank to wallet (Orange et Wave)", "Liaison du compte ECOBANK au portefeuille mobile", "4H", i++);
        activity(p, "Bank to wallet (Orange et Wave)", "Déliaison du compte ECOBANK au portefeuille mobile", "4H", i++);
        activity(p, "Compte", "Extraction de relevé de compte (BO)", "24H", i++);
        activity(p, "Compte", "Extraction de plan d'amortissement (BO)", "24H", i++);
        activity(p, "Compte", "Relevé de compte zone UEMOA", "1H", i++);
        activity(p, "Compte", "Informations client", "1H", i++);
        activity(p, "Compte", "Edition de RIB zone UEMOA", "1H", i++);
        activity(p, "Compte", "Vérification information sur le compte", "Instantanée", i++);
        activity(p, "Compte", "Création de profil e-alert", "24H", i++);
        activity(p, "Compte", "Modification de coordonnées dans e-alert (e-mail, numéro de téléphone)", "24H", i++);
        activity(p, "Compte", "Ajout de coordonnées dans e-alert (e-mail, numéro de téléphone)", "24H", i++);
        activity(p, "Compte", "Assistance du service clientèle via les discussions instantanées (tchats)", "Instantanée", i++);
        activity(p, "Compte", "Assistance et prise en charge sur les réseaux sociaux", "Instantanée", i);
    }

    // ---------- PÔLE OUTBOUND ----------
    private void seedOutbound(int sortOrder) {
        RccPole p = pole("Pôle Outbound", "hgiewa@ecobank.com", "0749597528",
                "Mail équipe : RCC_OUTBOUNDTEAM@ecobank.com", "RCC_OUTBOUNDTEAM@ecobank.com",
                "L'équipe de prise en charge des activités sortantes pour les particuliers.",
                "Appels sortants, mailings et Sms.", sortOrder);
        int i = 1;
        activity(p, "Appel", "Assistance sur l'utilisation des produits", "Instantanée", i++);
        activity(p, "Appel", "Confirmation de Transactions suspicieuses", "1H", i++);
        activity(p, "Appel", "Rappels clients", "10MN", i++);
        activity(p, "Appel", "Vente de produits digitaux : Cartes, Ecobank online, TPE, OMNILITE, Compte, Bank to wallet",
                "Selon le volume de la base", i++);
        activity(p, "Appel", "Propositions commerciales & détection de leads", "Selon le volume de la base", i++);
        activity(p, "Mailing", "Information sur produits", "24H", i++);
        activity(p, "Mailing", "Informations clients", "24H", i++);
        activity(p, "Mailing", "Conduite d'enquêtes / Sondages", "24H", i++);
        activity(p, "Mailing", "Campagnes sur produits", "24H", i++);
        activity(p, "Campagne SMS", "Information sur produits", "24H", i++);
        activity(p, "Campagne SMS", "Campagnes sur produits", "24H", i++);
        activity(p, "Campagne SMS", "SMS anniversaire", "24H", i++);
        activity(p, "Campagne SMS", "Célébration prénom du Jour", "24H", i++);
        activity(p, "Campagne SMS", "SMS aux clients ayant abandonné l'appel", "24H", i);
    }

    // ---------- PÔLE RÉSOLUTION ----------
    private void seedResolution(int sortOrder) {
        RccPole p = pole("Pôle Résolution", "rsika@ecobank.com", "0707005644",
                "Numéro vert : 9955 — Numéro long : 2721210021", "assist@ecobank.com",
                "L'équipe en charge des CAS de niveau 2 du RCC.",
                "En collaboration étroite avec les filiales et les équipes de back office, l'équipe traite les réclamations des clients et effectue leurs suivis jusqu'à résolution.",
                sortOrder);
        int i = 1;
        activity(p, "Carte", "Prélèvements injustifiés de frais de carte", "72H", i++);
        activity(p, "Carte", "Débit sans délivrance d'espèce sur nos GAB", "72H", i++);
        activity(p, "Carte", "Débit sans délivrance d'espèce sur GAB confrère", "7 jours", i++);
        activity(p, "Carte", "Paiement non abouti sur TPE ou en ligne", "30 à 45 jours", i++);
        activity(p, "Compte", "Demande de mise à jour des informations du client non prises en compte",
                "Réclamation ré-adressée à l'agence — 48H", i++);
        activity(p, "Compte", "Instructions de procuration non exécutées", "Réclamation ré-adressée à l'agence — 48H", i++);
        activity(p, "Compte", "Demande d'ouverture de compte non effectuée", "Réclamation ré-adressée à l'agence — 48H", i++);
        activity(p, "Compte", "Souscription contestée (bancassurance, FP pack...)", "Réclamation ré-adressée à l'agence — 48H", i++);
        activity(p, "Compte", "Commande de carte magnétique non effectuée", "Réclamation ré-adressée à l'agence — 48H", i++);
        activity(p, "Compte", "Retard dans le traitement de la remise chèque", "Réclamation ré-adressée à l'agence — 48H", i++);
        activity(p, "Compte", "Demande de chéquier restée sans suite", "Réclamation ré-adressée à l'agence — 48H", i++);
        activity(p, "Compte", "Opposition sur chèque non prise en compte", "1H", i++);
        activity(p, "Compte", "Versement GAB non abouti", "5 jours", i++);
        activity(p, "Compte", "Versement agency banking non abouti", "5 jours", i++);
        activity(p, "Compte", "Demande d'attestation bancaire restée sans suite", "5 jours", i++);
        activity(p, "Compte", "Demande d'authentification de documents bancaires restée sans suite", "72H", i++);
        activity(p, "Compte", "Demande d'activation option virement restée sans suite",
                "Réclamation ré-adressée à l'agence — 48H", i++);
        activity(p, "Compte", "Demande de prêt restée sans suite", "Réclamation ré-adressée à l'agence — 48H", i++);
        activity(p, "Compte", "Demande de reverse de cash coll restée sans suite",
                "Réclamation ré-adressée à l'agence — 48H", i++);
        activity(p, "Compte", "Fonds ou virements non perçus", "72H", i++);
        activity(p, "Compte", "Rejet de salaire", "72H", i++);
        activity(p, "Compte", "Transfert non abouti", "72H", i++);
        activity(p, "Compte", "Appels de fonds / RT non exécuté", "72H", i++);
        activity(p, "Compte", "Demande de Swift", "24H", i++);
        activity(p, "Produits digitaux", "Echec de transaction B2W ou W2B", "72H", i++);
        activity(p, "Produits digitaux", "Débit à tort E Token", "72H", i++);
        activity(p, "Produits digitaux", "Echec de paiement airtime", "10 jours", i++);
        activity(p, "Produits digitaux", "Paiement de facture CIE/SOCEDI/CANAL non abouti", "72H", i);
    }

    // ---------- PÔLE OPÉRATIONS ----------
    private void seedOperations(int sortOrder) {
        RccPole p = pole("Pôle Opérations", "ekouakou@ecobank.com", "0719285632",
                null, null,
                "L'équipe chargée des maintenances sur cartes et de l'analyse des données du RCC.",
                "Des maintenances sur cartes, autorisations d'actions initiées sur les plateformes, Gestion des données et factures filiales.",
                sortOrder);
        int i = 1;
        activity(p, "Carte de débit", "Blocage de carte", "10MN", i++);
        activity(p, "Carte de débit", "Changement de limite", "5H", i++);
        activity(p, "Carte de débit", "Réinitialisation du code Pin", "1H", i++);
        activity(p, "Carte de débit", "Réclamations/ questions relatives aux cartes", "2H", i++);
        activity(p, "Carte de débit", "Activation de carte", "5H", i++);
        activity(p, "Carte de débit", "Changement de priorité sur carte", "5H", i++);
        activity(p, "Ecobank Online", "Création et activation", "24H", i++);
        activity(p, "Ecobank Online", "Réinitialisation profil/mot de passe", "24H", i++);
        activity(p, "Ecobank Online", "Verrouillage / Révocation / Désactivation", "24H", i++);
        activity(p, "Ecobank Online", "Assistance", "Instantané", i++);
        activity(p, "Mobile App", "Réinitialisation du code Pin", "24H", i++);
        activity(p, "Mobile App", "Ajout de compte", "24H", i++);
        activity(p, "Mobile App", "Désactivation de profil", "24H", i++);
        activity(p, "Mobile App", "Réactivation de profil", "24H", i++);
        activity(p, "Mobile App", "Onboarding / Création de profil", "24H", i++);
        activity(p, "Mobile App", "Assistance à l'installation de profil", "Instantanée", i++);
        activity(p, "Bank to wallet (exclusivement pour Ecobank Côte d'Ivoire)", "Ajout de compte Mobile money", "4H", i++);
        activity(p, "Bank to wallet (exclusivement pour Ecobank Côte d'Ivoire)", "Delinkage de compte Mobile money", "4H", i++);
        activity(p, "Compte (exclusivement pour Ecobank Côte d'Ivoire)", "Activation alerte électronique/SMS", "24H", i++);
        activity(p, "Compte (exclusivement pour Ecobank Côte d'Ivoire)", "Désactivation alerte électronique/SMS", "24H", i);
    }

    // ---------- PÔLE BUSINESS (petites/grandes entreprises et Agency Banking) ----------
    private void seedBusiness(int sortOrder) {
        RccPole p = pole("Pôle Business & Agency Banking", "asitionon@ecobank.com", "0747569056",
                "Numéro : 2721599199", "businessassist@ecobank.com",
                "L'équipe en charge des petites/grandes entreprises et Agency Banking.",
                "Appels entrants, mails, CIS, appels sortants.", sortOrder);
        int i = 1;
        activity(p, "Compte", "Relevé de compte", "24H", i++);
        activity(p, "Compte", "Avis de crédit / Débit", "24H", i++);
        activity(p, "Compte", "Relevé des frais /Echelle d'intérêt", "24H", i++);
        activity(p, "Compte", "Fichier encaissement des assureurs", "24H", i++);
        activity(p, "Compte", "Suivi de demande de chèque et disponibilité", "24H", i++);
        activity(p, "Compte", "Prise en charge des réclamations", "24H", i++);
        activity(p, "Carte prépayée Business prepaid card", "Demande de solde", "24H", i++);
        activity(p, "Carte prépayée Business prepaid card", "Edition de code Pin / Edition de web code", "24H", i++);
        activity(p, "Carte prépayée Business prepaid card", "Demande de relevé et assistance", "24H", i++);
        activity(p, "OMNIPLUS", "Déverrouillage / Réinitialisation de code", "24H", i++);
        activity(p, "OMNIPLUS", "Assistance à l'utilisation", "24H", i++);
        activity(p, "OMNIPLUS", "Transmission de QR code pour le token", "24H", i++);
        activity(p, "OMNIPLUS", "Prise en compte et suivi des réclamations", "24H", i++);
        activity(p, "OMNILITE", "Modification utilisateur / Ajout utilisateur", "24H", i++);
        activity(p, "OMNILITE", "Mise à jour de préférence de partie / limites de paiement", "24H", i++);
        activity(p, "OMNILITE", "Mapping de user / Mapping de compte", "24H", i++);
        activity(p, "OMNILITE", "Création ou mise à jour workflow / Rule management", "24H", i++);
        activity(p, "OMNILITE", "Configuration des identifiants du fichier de paiement", "24H", i++);
        activity(p, "OMNILITE", "Mapping des utilisateurs au fichier de paiement", "24H", i++);
        activity(p, "OMNILITE", "Réinitialisation du mot de passe / Déverrouillage de profil", "24H", i++);
        activity(p, "OMNILITE", "Configuration party to party", "24H", i++);
        activity(p, "ECOBANK PAY", "Information / Assistance / Reset code", "Instantané", i++);
        activity(p, "ECOBANK PAY", "Réclamation", "Non défini", i++);
        activity(p, "TPE", "Assistance", "Instantané", i++);
        activity(p, "TPE", "Transmission de chargeback", "24H", i++);
        activity(p, "TPE", "Prise en compte et suivi des réclamations", "Non défini", i++);
        activity(p, "Agency Banking", "Gestion des Sous Agents", "24H", i++);
        activity(p, "Agency Banking", "Gestion des terminaux des agents", "24H", i++);
        activity(p, "Agency Banking", "Changement de mot de passe", "24H", i++);
        activity(p, "Agency Banking", "Gestion des comptes GL Filiale / Agent", "24H", i++);
        activity(p, "Agency Banking", "Désactivation des Sous Agents", "24H", i++);
        activity(p, "Agency Banking", "Consultation des transactions", "24H", i++);
        activity(p, "Agency Banking", "Réclamation et incident", "24H", i);
    }

    // ---------- AGENCES (référentiel réseau — pas de manager/contact unique) ----------
    private void seedAgences(int sortOrder) {
        RccPole p = pole("Agences", null, null, null, null,
                "Référentiel des délais de traitement standard en agence.", null, sortOrder);
        int i = 1;
        activity(p, "Mobile app", "Création et activation", "Instantanée", i++);
        activity(p, "Mobile app", "Reset code", "Instantanée", i++);
        activity(p, "Mobile app", "Ajout de compte", "Instantanée", i++);
        activity(p, "Mobile app", "modification du numéro de téléphone", "15 min", i++);
        activity(p, "Mobile app", "suppression du profil", "15 min", i++);
        activity(p, "Ecobank Online", "Création et activation", "Instantanée", i++);
        activity(p, "Ecobank Online", "Reset code pin", "Instantanée", i++);
        activity(p, "Ecobank Online", "Activation option virement", "Instantanée", i++);
        activity(p, "Carte", "Edition de carte magnétique", "Instantanée", i++);
        activity(p, "Carte", "Edition du code pin", "Instantanée", i++);
        activity(p, "Carte", "Ajout de compte (action transmise au rcc)", "24H", i++);
        activity(p, "Carte", "Blocage de carte", "Instantanée", i++);
        activity(p, "Carte prépayée", "Edition de carte cashxpress anonyme", "Instantanée", i++);
        activity(p, "Carte prépayée", "Edition de carte cashxpress personnalisée", "72H", i++);
        activity(p, "Carte prépayée", "Rechargement de carte cashxpress", "Instantanée", i++);
        activity(p, "Carte prépayée", "Réédition de code pin", "Instantanée", i++);
        activity(p, "Carte prépayée", "modification des données", "Instantanée", i++);
        activity(p, "Bank to wallet (Orange et Wave)", "Liaison du compte ECOBANK au portefeuille mobile", "Instantanée", i++);
        activity(p, "Bank to wallet (Orange et Wave)", "Déliaison du compte ECOBANK au portefeuille mobile", "Instantanée", i++);
        activity(p, "Compte", "Informations client", "Instantanée", i++);
        activity(p, "Compte", "Ouverture de compte particulier", "Instantanée si la documentation est complète", i++);
        activity(p, "Compte", "Ouverture de compte particulier AOP", "24 à 48H", i++);
        activity(p, "Compte", "Ouverture de compte entreprise", "24 à 48H selon le retour de la cellule", i++);
        activity(p, "Compte", "Demande de solde", "Instantanée", i++);
        activity(p, "Compte", "Demande de relevé de compte", "Instantanée", i++);
        activity(p, "Compte", "Extraction du plan d'amortissement", "Instantanée", i++);
        activity(p, "Compte", "Edition du RIB", "Instantanée", i++);
        activity(p, "Compte", "Mise à jour des informations du client (coordonnées, signature, instructions spéciales, procuration...)",
                "Instantanée si reçue dans l'agence de domiciliation et 72H si transmise dans une autre agence", i++);
        activity(p, "Compte", "Mise à jour des informations du client (Data clean up)", "Instantanée", i++);
        activity(p, "Compte", "Demande d'attestation de redevance", "3 semaines", i++);
        activity(p, "Compte", "Demande d'attestation de non redevance", "48H", i++);
        activity(p, "Compte", "Demande d'attestation de solde, bancaire, virement permanent", "48H", i++);
        activity(p, "Compte", "Remise chèque", "Instantanée", i++);
        activity(p, "Compte", "Commande de chéquier", "Instantanée", i++);
        activity(p, "Compte", "Demande de prêt", "instantanée si documentation complète", i++);
        activity(p, "Compte", "Souscription EAA", "Instantanée", i++);
        activity(p, "Compte", "Mise en place OVP", "Instantanée", i++);
        activity(p, "Compte", "clôture de compte", "72H", i++);
        activity(p, "Compte", "Ordre de transfert", "Instantanée", i++);
        activity(p, "Réclamations", "Réception de tout type de réclamation", "Instantanée", i++);
        activity(p, "Bancassurance", "Souscription bancassurance", "Instantanée", i++);
        activity(p, "Bancassurance", "Réception réclamation sur un produit de bancassurance (rachat, prélèvements contestés)",
                "Non défini", i);
    }
}

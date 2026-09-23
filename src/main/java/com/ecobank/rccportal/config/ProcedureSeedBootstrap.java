package com.ecobank.rccportal.config;

import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Peuple les Procédures au démarrage à partir de la nomenclature réelle
 * Ecobank (document "UNIFORMISATION DES PRISES EN CHARGE") — 120 intitulés de
 * cas réels répartis en 10 catégories.
 *
 * IMPORTANT — honnêteté sur le contenu : le document source ne contient QUE
 * les intitulés (une nomenclature/index), aucune étape de traitement. Chaque
 * fiche créée ici a donc un contenu générique "En attente de rédaction par
 * QA" plutôt que des étapes inventées — inventer des étapes pour de vrais
 * sujets bancaires (succession, contestation carte, prêt...) serait risqué
 * pour un agent qui s'y fierait avec un vrai client. QA/Admin les complètent
 * ensuite normalement depuis l'écran Procédures (déjà entièrement éditable).
 *
 * Une seule fiche fait exception : "COMPTE – DEMANDE DE SOLDE" reçoit un
 * vrai parcours interactif complet ("Consultation de solde"), reproduit
 * fidèlement depuis la maquette fournie — un modèle que QA/Admin peuvent
 * dupliquer pour les 119 autres fiches.
 *
 * Idempotent : ne recrée rien si la zone/fiche existe déjà (recherche par
 * code de zone / titre exact).
 */
@Slf4j
@Component
@Order(20) // après DevAccountsBootstrap (comptes) et WorkflowSchemaBootstrap (colonnes/tables)
public class ProcedureSeedBootstrap implements CommandLineRunner {

    private static final String PLACEHOLDER_STEP =
            "En attente de rédaction par QA — ce titre provient de la nomenclature Ecobank, " +
            "le contenu détaillé (étapes de traitement) reste à écrire.";

    private static final Map<String, String> ZONE_LABELS = Map.ofEntries(
            Map.entry("COMPTE", "Compte"),
            Map.entry("TRANSFERT", "Transfert"),
            Map.entry("ATTESTATION_DOCUMENTS", "Attestation & Documents"),
            Map.entry("CARTE", "Carte"),
            Map.entry("SERVICES_DIGITAUX", "Services Digitaux"),
            Map.entry("ASSURANCE_CREDIT", "Assurance & Crédit"),
            Map.entry("RECLAMATION_ESCALADE", "Réclamation & Escalade"),
            Map.entry("PRETS_BANCAIRES", "Prêts Bancaires"),
            Map.entry("RESEAUX_SOCIAUX_MAILS", "Réseaux Sociaux & Mails"),
            Map.entry("AUTRES_SERVICES", "Autres Services")
    );

    private static final Map<String, List<String>> PROCEDURE_TITLES_BY_ZONE = Map.ofEntries(
            Map.entry("COMPTE", List.of("CONSTITUTION DE DAT NON EFFECTUEE", "COMPTE – CONSTESTATION DU MONTANT DES INTERÊTS CREDITEURS", "COMPTE – DEMANDE DE CHANGEMENT DE SIGNATAIRES", "COMPTE – DEMANDE DE CHANGEMENT DE SIGNATAIRES NON-PRISE EN COMPTE", "COMPTE – DEMANDE DE CODE SWIFT DE ECOBANK CI", "COMPTE – DEMANDE DE LEVEE DE RESTRICTION", "COMPTE – DEMANDE DE LEVEE DE RESTRICTION RESTEE SANS SUITE", "COMPTE – DEMANDE DE MISE A JOUR DES INFORMATIONS", "COMPTE – DEMANDE DE MISE A JOUR DES INFORMATIONS RESTEE SANS SUITE", "COMPTE – DEMANDE DE MISE DE RESTRICTION (NO DEBIT OU COMPTE DORMANT)", "COMPTE – DEMANDE DE MISE DE RESTRICTION RESTEE SANS SUITE (NO DEBIT OU COMPTE DORMANT)", "COMPTE – DEMANDE DE SOLDE", "COMPTE – DEMANDE D’OUVERTURE DE COMPTE", "COMPTE – DEMANDE D’OUVERTURE DE COMPTE RESTEE SANS SUITE", "COMPTE – PRELEVEMENT CONTESTE", "COMPTE – SUCCESSION", "COMPTE – DEMANDE / SUPRESSION DE PROCURATION SU COMPTE", "COMPTE-DEMANDE CLOTURE DE COMPTE", "COMPTE – DEMANDE DE CLÔTURE DE COMPTE NON EFFECTUE", "COMPTE – DEMANDE / SUPRESSION DE PROCURATION SU COMPTE NON PRISE EN COMPTE", "CHEQUE- DEMANDE DE CHEQUIER RESTEE SANS SUITE", "CHEQUE – DEMANDE DE DISPONIBILITE DE CHEQUIER", "CHEQUE - DEMANDE D'OPPOSITION", "CHEQUE - LEVEE D'OPPOSITION", "CHEQUE – MOTIF DE REJET", "CHEQUE – REJETE NON REMIS", "CHEQUE – REMISE CHEQUE NON EFFECTUEE", "CHEQUE – RENOUVELLEMENT DE CHEQUIER", "CHEQUE – STATUT DEMANDE CHEQUIER")),
            Map.entry("TRANSFERT", List.of("DEMANDE D’ANNULATION DE TRANSFERT", "TRANSFERT-DEMANDE DE TRANSFERT DE FONDS", "TRANSFERT-DEMANDE DE TRANSFERT DE FONDS RESTEE SANS SUITE", "TRANSFERT CARTE A CARTE", "TRANSFERT CARTE A CARTE NON EFFECTUE", "TRANSFERT- CORRECTION DES INFORMATIONS DU BENEFICIAIRE", "TRANSFERT- CORRECTION DES INFORMATIONS DU BENEFICIAIRE NON PRIS EN COMPTE", "TRANSFERT DE FONDS EN DEVISE NON EFFECTUE", "TRANSFERT FRAIS ET CHARGES", "TRANSFERT FRAIS ET CHARGES INJUSTIFIES", "TRANSFERT- NON RECEPTION DE CODE RAPIDTRANSFER", "TRANSFERT- ORDRE DE VIREMENT", "TRANSFERT- ORDRE DE VIREMENT NON EXECUTE", "TRANSFERT-DEMANDE DE STATUT DE TRANSACTION (CAS TRANSACTION ABOUTIE)", "TRANSFERT-DEMANDE DE STATUT DE TRANSACTION (CAS TRANSACTION NON ABOUTIE)")),
            Map.entry("ATTESTATION_DOCUMENTS", List.of("DEMANDE D’ATTESTATION BANCAIRE (redevance et non redevance, etc.) RESTEE SANS SUITE", "DEMANDE D’ATTESTATION BANCAIRE (demande de relevé bancaire et demande RIB au RCC)", "DEMANDE D’ATTESTATION BANCAIRE (Attestation de redevance et non redevance)", "DEMANDE DE RACHAT D’ASSURANCE")),
            Map.entry("ASSURANCE_CREDIT", List.of("DEMANDE DE RESILIATION D’ASSURANCE RESTEE SANS SUITE")),
            Map.entry("CARTE", List.of("CARTE-DEBIT A TORT (GIM UEMOA) ET (HORS GIM UEMOA)", "CARTE- DEBIT A TORT DAB ECOBANK (AUTRE FILIALE)", "CARTE- DEBIT A TORT DAB ECOBANK (FILIALE)", "CARTE- DEBIT A TORT ETOKEN", "CARTE- DELIVRANCE PARTIELLE DE BILLET", "CARTE- PLAINTE LIEE AU GUICHET AUTOMATIQUE (GAB INDISPONIBLE, MANQUE DE LIQUIDITE, NON DELIVRANCE DE RECU…)", "CARTE – AUGMENTATION DE LIMITE (LIMIT CHANGE)", "CARTE – AUGMENTATION DE LIMITE NON EFFECTUEE", "CARTE - CARTE CAPTUREE", "CARTE - CARD COMPLAINT", "CARTE – CARTE NON FONCTIONNELLE", "CARTE – CARTE SUPPLEMENTAIRE", "CARTE – CODE PIN NON DELIVRE", "CARTE - COMMANDE (VISA, MASTERCARD, CASHXPRESS, ETC.)", "CARTE – COMMANDE RESTEE SANS SUITE (VISA/ MASTERCARD GOLD ET PLATINIUM)", "CARTE – CONTESTATION DU MONTANT PERCU AU DAB", "CARTE - CONTESTATION FRAIS DE TRANSACTIONS", "CARTE – DEBLOCAGE VBV / REINITIALISATION PASS OU WEB CODE CASH XPRESS", "CARTE - DEBLOCAGE", "CARTE- DEBLOCAGE NON EFFECTUE", "CARTE – DEMANDE DE LINKAGE", "CARTE - DEMANDE DE LINKAGE RESTEE SANS SUITE", "CARTE – DEMANDE DE TRAVELS NOTICE", "CARTE – PRELEVEMENT DES FRAIS / CHARGES INJUSTIFIE", "CARTE -RECHARGEMENT NON EFFECTUE (EN AGENCE OU VIA ECOBANK MOBILE)", "CARTE- REEDITION CODE PIN", "CHEQUE – DEMANDE DE CHEQUIER", "CARTE - ACHAT NON ABOUTI/TROP PERCU/REMBOURSEMENT NON EFFECTIF TPE / EN LIGNE", "PIN RESET/ PASS RESET CARTE CASHXPRESS", "DEBLOCAGE VBV CASHXPRESS", "CASHXPRESS – ACHAT DE LA CARTE", "CASHXPRESS-TRANSACTION NON ABOUTIE", "CASHXPRESS-BLOCAGE DE CARTE", "CASHXPRESS-RECHARGEMENT NON EFFECTUE")),
            Map.entry("SERVICES_DIGITAUX", List.of("ECOBANK ONLINE-ACTIVATION NON EFFECTUEE", "ECOBANK ONLINE-ACTIVATION OPTION VIREMENT EN LIGNE", "ECOBANK ONLINE-ACCES AUX INFORMATIONS IMPOSSIBLE", "ECOBANK ONLINE-DEVEROUILLAGE ET REINITIALISATION DE MOT DE PASSE", "ECOBANK ONLINE-DIFFICULTE A EFFECTUER DES TRANSACTIONS/TRANSFERT IMPOSSIBLE/NON RECEPTION OTP", "ECOBANK ONLINE-VERROUILLAGE UTILISATEUR NON EFFECTUE", "ECOBANK PAY-INSTALLATION NON EFFECTUEE", "ACCES IMPOSSIBLE AUX INFORMATIONS -ECOBANK MOBILE APP", "ACCES IMPOSSIBLE AUX INFORMATIONS -ECOBANK ONLINE", "ECOBANK MOBILE APP – AJOUT DE COMPTE", "VIREMENT BANCAIRE VIA ECOBANK ONLINE / ECOBANK MOBILE", "ACHAT DE CREDIT TELEPHONIQUE NON ABOUTI (MOBILE APP)", "ECOBANK MOBILE APP – DEMANDE DE REINITIALISATON DE MOT DE PASSE/ DEMANDE DE SUPPRESSION DE PROFIL", "ECOBANK MOBILE APP – DIFFICULTE A EFFECTUER DES TRANSACTIONS / DIFFICULTE A GENERER UN E-TOKEN/NON RECEPTION OTP/TRANSFERT IMPOSSIBLE", "B2W/W2B – ASSISTANCE (PROCEDURE DE TRANSFERT)", "B2W/W2B – DEMANDE SOUSCRIPTION", "B2W/W2B – TRANSFERT MOBILE MONEY NON ABOUTI")),
            Map.entry("PRETS_BANCAIRES", List.of("DEMANDE DE PRET RESTEE SANS SUITE- FILIALE SENEGAL", "DEMANDE DE PRET- AUTRE FILIALE", "DEMANDE DE PRET RESTEE SANS SUITE- AUTRE FILIALE", "PRET-DEMANDE DE RACHAT DE PRET", "PRET-DEMANDE DE RACHAT DE PRET RESTEE SANS SUITE", "PRET-DEMANDE DE REVERSE DE CASH COLL", "PRET-DEMANDE DE REVERSE DE CASH COLL RESTEE SANS SUITE")),
            Map.entry("AUTRES_SERVICES", List.of("AUTRE-HOMMAGE", "PAIEMENT DE TIMBRE DE PASSEPORT", "AUTRE-SUIVI DE TRANSACTION SUSPECTE", "AUTRE-PAIEMENT DE FACTURE", "AUTRE-VIREMENT-NON RECEPTION DE LA BOURSE FILIALE SENEGAL", "AUTRE-VIREMENT SALAIRE/PENSION", "AUTRE – DOCUMENTS EGARES (CARTE MAGNETIQUE)", "AUTRE – DOCUMENTS EGARES (CNI EGAREE)", "AUTRE-AVANCE SUR SALAIRE – SOUSCRIPTION / RENOUVELLMENT", "AUTRE-E-ALERT / E-STATEMENT-DEMANDE DE SOUSCRIPTION", "AUTRE-E-ALERT / E-STATEMENT-DEMANDE DE SOUSCRIPTION RESTEE SANS SUITE")),
            Map.entry("RECLAMATION_ESCALADE", List.of("AUTRE-SUIVI DE RECLAMATION", "ATTITUDE STAFF – ABUS DE CONFIANCE, DISCOURTOISIE DE L’AGENT, PLAINTE LIEE A UNE AGENCE")),
            Map.entry("RESEAUX_SOCIAUX_MAILS", List.of())
    );

    private final ProcedureZoneRepository zoneRepository;
    private final ProcedureRepository procedureRepository;
    private final ProcedureStepRepository stepRepository;
    private final ProcedureWorkflowNodeRepository nodeRepository;
    private final ProcedureWorkflowOptionRepository optionRepository;

    public ProcedureSeedBootstrap(
            ProcedureZoneRepository zoneRepository,
            ProcedureRepository procedureRepository,
            ProcedureStepRepository stepRepository,
            ProcedureWorkflowNodeRepository nodeRepository,
            ProcedureWorkflowOptionRepository optionRepository) {
        this.zoneRepository = zoneRepository;
        this.procedureRepository = procedureRepository;
        this.stepRepository = stepRepository;
        this.nodeRepository = nodeRepository;
        this.optionRepository = optionRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        int zonesCreated = 0;
        int proceduresCreated = 0;

        for (Map.Entry<String, String> zoneEntry : ZONE_LABELS.entrySet()) {
            String zoneCode = zoneEntry.getKey();
            ProcedureZone zone = zoneRepository.findByCode(zoneCode).orElse(null);
            if (zone == null) {
                zone = zoneRepository.save(ProcedureZone.builder().code(zoneCode).label(zoneEntry.getValue()).build());
                zonesCreated++;
            }

            List<Procedure> existing = procedureRepository.findByZoneOrderByTitleAsc(zone);
            java.util.Set<String> existingTitles = new java.util.HashSet<>();
            for (Procedure p : existing) existingTitles.add(p.getTitle().trim().toLowerCase());

            for (String title : PROCEDURE_TITLES_BY_ZONE.getOrDefault(zoneCode, List.of())) {
                if (existingTitles.contains(title.trim().toLowerCase())) continue;

                Procedure procedure = procedureRepository.save(
                        Procedure.builder().zone(zone).title(title).build());
                stepRepository.save(ProcedureStep.builder()
                        .procedure(procedure).stepNumber(1).content(PLACEHOLDER_STEP).build());
                proceduresCreated++;
            }
        }

        if (zonesCreated > 0 || proceduresCreated > 0) {
            log.warn("⚠ [PROCEDURE SEED] {} zone(s) et {} fiche(s) créées depuis la nomenclature Ecobank.",
                    zonesCreated, proceduresCreated);
        }

        seedConsultationSoldeWorkflow();
        seedRccCallFlows();
    }

    /**
     * Parcours interactifs (call flows) issus du guide opérationnel RCC —
     * "Quelles questions poser / Comment authentifier / N1 ou N2 / SLA".
     * Rattachés aux fiches existantes de la nomenclature (COMPTE, TRANSFERT,
     * CARTE, SERVICES_DIGITAUX, ATTESTATION_DOCUMENTS, PRETS_BANCAIRES,
     * RECLAMATION_ESCALADE). Idempotent (ne touche pas les fiches qui ont déjà
     * un parcours).
     */
    private void seedRccCallFlows() {
        seedLinearWorkflow("COMPTE", "CONSTITUTION DE DAT NON EFFECTUEE", List.of(
                "Nom et prénom du client ?",
                "Agence où la demande de DAT a été effectuée ?",
                "Date de la demande ?",
                "Montant à octroyer et numéro de compte ?",
                "Authentifier le client",
                "Créer Incident Niveau 2 et affecter à RCC Résolution",
                "Envoyer mail au service Résolution"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "COMPTE – DEMANDE DE CODE SWIFT DE ECOBANK CI", List.of(
                "Nom, prénom et numéro de compte ?",
                "Authentifier le client",
                "Proposer l'envoi du RIB (contenant le code SWIFT) par mail",
                "Collecter l'adresse mail",
                "Envoyer au service Digital — Incident Niveau 1"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "COMPTE – DEMANDE D’OUVERTURE DE COMPTE", List.of(
                "Nom et prénom du client ?",
                "Client résident ou non résident ?",
                "Résident : informer sur la pièce d'identité, les documents requis et la présence obligatoire en agence",
                "Non résident : orienter vers www.aop.ecobank.com",
                "Créer Incident Niveau 1"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "COMPTE – DEMANDE D’OUVERTURE DE COMPTE RESTEE SANS SUITE", List.of(
                "Nom, prénom, agence, date de la demande ?",
                "Contact et e-mail fournis ? CNI fournie ? Versement effectué ?",
                "Rechercher le compte : si trouvé, authentifier et communiquer le numéro — clôturer",
                "Si non trouvé : créer Incident Niveau 2 et affecter à RCC Résolution"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "COMPTE – DEMANDE DE CHANGEMENT DE SIGNATAIRES", List.of(
                "Nom, prénom, numéro de compte et agence ?",
                "Quelles informations doivent être modifiées ?",
                "Authentifier le client",
                "Créer Incident Niveau 2 et envoyer mail à RCC Résolution"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "COMPTE – DEMANDE DE CHANGEMENT DE SIGNATAIRES NON-PRISE EN COMPTE", List.of(
                "Nom, prénom, numéro de compte et agence ?",
                "Date de la demande initiale ?",
                "Authentifier le client",
                "Créer Incident Niveau 2 et envoyer mail à RCC Résolution"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "COMPTE – DEMANDE DE LEVEE DE RESTRICTION", List.of(
                "Nom et prénom du client ?",
                "Informer : se rendre en agence pour remplir le formulaire de levée de restriction",
                "Créer Incident Niveau 1"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "COMPTE – DEMANDE DE LEVEE DE RESTRICTION RESTEE SANS SUITE", List.of(
                "Numéro de compte, date et agence ?",
                "Authentifier le client",
                "Créer Incident Niveau 2"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "COMPTE – DEMANDE DE MISE A JOUR DES INFORMATIONS", List.of(
                "Nom et prénom du client ?",
                "Informer : se rendre en agence pour effectuer la mise à jour",
                "Créer Incident Niveau 1"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "COMPTE – DEMANDE DE MISE A JOUR DES INFORMATIONS RESTEE SANS SUITE", List.of(
                "Date de la demande et agence ?",
                "Contacts à ajouter/modifier ?",
                "Créer Incident Niveau 2 et envoyer mail à RCC Résolution"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "COMPTE-DEMANDE CLOTURE DE COMPTE", List.of(
                "Pourquoi le client souhaite-t-il clôturer le compte ?",
                "Tenter de retenir le client",
                "Si maintien de la demande : rédiger un courrier de clôture à déposer en agence",
                "Créer Incident Niveau 1"), "FIDELISATION");

        seedLinearWorkflow("COMPTE", "COMPTE – DEMANDE DE CLÔTURE DE COMPTE NON EFFECTUE", List.of(
                "Agence, date et numéro de compte ?",
                "Authentifier le client",
                "Créer Incident Niveau 2"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "CHEQUE - DEMANDE D'OPPOSITION", List.of(
                "Numéro du chèque et motif de l'opposition ?",
                "Créer Incident Niveau 2",
                "Orienter vers l'agence pour la déclaration de perte"), "CLOTURE");

        seedLinearWorkflow("COMPTE", "CHEQUE - LEVEE D'OPPOSITION", List.of(
                "Informer : déplacement obligatoire en agence pour la levée d'opposition",
                "Créer Incident Niveau 1"), "CLOTURE");

        seedLinearWorkflow("TRANSFERT", "DEMANDE D’ANNULATION DE TRANSFERT", List.of(
                "Expéditeur et bénéficiaire ?",
                "Banque émettrice et banque réceptrice ?",
                "Montant, date, référence et motif ?",
                "Authentifier le client",
                "Créer Incident Niveau 2"), "CLOTURE");

        seedLinearWorkflow("TRANSFERT", "TRANSFERT- CORRECTION DES INFORMATIONS DU BENEFICIAIRE", List.of(
                "Ancien bénéficiaire et nouveau bénéficiaire ?",
                "Référence et montant du virement ?",
                "Créer Incident Niveau 2"), "CLOTURE");

        seedLinearWorkflow("ATTESTATION_DOCUMENTS", "DEMANDE D’ATTESTATION BANCAIRE (Attestation de redevance et non redevance)", List.of(
                "Type de document demandé ?",
                "Agence et date de la demande ?",
                "Contact du client ?",
                "Créer Incident Niveau 2 — SLA : 5 jours ouvrés"), "CLOTURE");

        seedLinearWorkflow("ATTESTATION_DOCUMENTS", "DEMANDE D’ATTESTATION BANCAIRE (demande de relevé bancaire et demande RIB au RCC)", List.of(
                "Période souhaitée pour le relevé ?",
                "Adresse e-mail du client ?",
                "Client ECI : envoi via service Digital — SLA 24h",
                "Autre filiale : créer Incident Niveau 2 — SLA 48h"), "CLOTURE");

        seedLinearWorkflow("ATTESTATION_DOCUMENTS", "DEMANDE DE RACHAT D’ASSURANCE", List.of(
                "Numéro de police et type de produit ?",
                "Agence du client ?",
                "Nouvelle demande : déposer un courrier en agence — Incident Niveau 1",
                "Suivi de rachat : créer Incident Niveau 2 — SLA 5 jours ouvrés"), "CLOTURE");

        seedLinearWorkflow("CARTE", "CARTE - CARTE CAPTUREE", List.of(
                "GAB Ecobank ou GAB autre banque ?",
                "GAB Ecobank : orienter le client vers l'agence pour récupérer la carte — Incident Niveau 1",
                "GAB autre banque : bloquer la carte, déclarer la perte, lancer le renouvellement et proposer Xpress Cash"), "CLOTURE");

        seedLinearWorkflow("CARTE", "CARTE – COMMANDE RESTEE SANS SUITE (VISA/ MASTERCARD GOLD ET PLATINIUM)", List.of(
                "Client résident ou non résident ?",
                "Résident : relancer la commande — créer Incident Niveau 2",
                "Non résident : orienter vers ecobankenquiries@ecobank.com"), "CLOTURE");

        seedLinearWorkflow("CARTE", "CARTE – CODE PIN NON DELIVRE", List.of(
                "Client résident ou non résident ?",
                "Résident : orienter vers l'agence",
                "Non résident : relancer par mail"), "CLOTURE");

        seedLinearWorkflow("CARTE", "CARTE- DEBIT A TORT DAB ECOBANK (FILIALE)", List.of(
                "Depuis combien de temps le débit a-t-il eu lieu ?",
                "Moins de 24h : informer le client d'attendre la reverse automatique",
                "Au-delà de 24h : créer Incident Niveau 2 — SLA 2 jours ouvrés"), "CLOTURE");

        seedLinearWorkflow("CARTE", "CARTE- DELIVRANCE PARTIELLE DE BILLET", List.of(
                "Montant demandé et montant réellement reçu ?",
                "Créer Incident Niveau 2 — SLA 5 jours ouvrés"), "CLOTURE");

        seedLinearWorkflow("CARTE", "CARTE- PLAINTE LIEE AU GUICHET AUTOMATIQUE (GAB INDISPONIBLE, MANQUE DE LIQUIDITE, NON DELIVRANCE DE RECU…)", List.of(
                "Détails du retrait non servi (date, GAB, montant) ?",
                "Créer Incident Niveau 2 — SLA 30 jours ouvrés"), "CLOTURE");

        seedLinearWorkflow("SERVICES_DIGITAUX", "ECOBANK ONLINE-ACTIVATION NON EFFECTUEE", List.of(
                "Adresse e-mail du client ?",
                "Carte magnétique et compte actif vérifiés ?",
                "Assister à la création du profil — Incident Niveau 1"), "CLOTURE");

        seedLinearWorkflow("SERVICES_DIGITAUX", "ECOBANK ONLINE-DEVEROUILLAGE ET REINITIALISATION DE MOT DE PASSE", List.of(
                "Le compte Ecobank Online est-il bloqué ?",
                "Si bloqué : réinitialiser — Incident Niveau 1",
                "Sinon : créer Incident Niveau 2"), "CLOTURE");

        seedLinearWorkflow("SERVICES_DIGITAUX", "ECOBANK ONLINE-ACTIVATION OPTION VIREMENT EN LIGNE", List.of(
                "Adresse e-mail du client ?",
                "Pièce d'identité fournie ?",
                "Créer Incident Niveau 2"), "CLOTURE");

        seedLinearWorkflow("SERVICES_DIGITAUX", "ECOBANK MOBILE APP – DEMANDE DE REINITIALISATON DE MOT DE PASSE/ DEMANDE DE SUPPRESSION DE PROFIL", List.of(
                "Numéro de téléphone du client ?",
                "Message d'erreur affiché ?",
                "Réinitialiser le profil ou créer Incident Niveau 2"), "CLOTURE");

        seedLinearWorkflow("SERVICES_DIGITAUX", "ECOBANK MOBILE APP – AJOUT DE COMPTE", List.of(
                "Ajouter un compte Ecobank, un autre profil ou un compte d'une autre filiale ?",
                "Assister l'ajout — Incident Niveau 1"), "CLOTURE");

        seedLinearWorkflow("SERVICES_DIGITAUX", "B2W/W2B – DEMANDE SOUSCRIPTION", List.of(
                "Numéro de téléphone et CNI ?",
                "Clé d'activation ?",
                "Vérifier et activer la liaison du compte"), "CLOTURE");

        seedLinearWorkflow("SERVICES_DIGITAUX", "B2W/W2B – TRANSFERT MOBILE MONEY NON ABOUTI", List.of(
                "Détails de la transaction Mobile Money non aboutie ?",
                "Créer Incident Niveau 2 — SLA 5 jours ouvrés"), "CLOTURE");

        seedLinearWorkflow("PRETS_BANCAIRES", "DEMANDE DE PRET- AUTRE FILIALE", List.of(
                "Employeur, salaire et type de contrat ?",
                "Montant demandé et type de prêt ?",
                "Contact, e-mail et CNI ?",
                "Transmettre au support crédit — retour sous 72h ouvrées"), "CLOTURE");

        seedLinearWorkflow("PRETS_BANCAIRES", "DEMANDE DE PRET RESTEE SANS SUITE- AUTRE FILIALE", List.of(
                "Agence, date, montant et type de prêt ?",
                "Créer Incident Niveau 2"), "CLOTURE");

        seedLinearWorkflow("RECLAMATION_ESCALADE", "ATTITUDE STAFF – ABUS DE CONFIANCE, DISCOURTOISIE DE L’AGENT, PLAINTE LIEE A UNE AGENCE", List.of(
                "Nom du collaborateur concerné, agence, date ?",
                "Motif et niveau de satisfaction du client ?",
                "Détails de la plainte ?",
                "Créer Incident Niveau 2 si nécessaire et transmettre au service Qualité"), "CLOTURE");
    }

    /**
     * Construit un parcours interactif linéaire (chaîne de nœuds/options) pour une
     * fiche existante identifiée par zone + titre exact. N'écrit rien si la fiche
     * est introuvable ou si elle a déjà un parcours (déjà édité par QA/Admin).
     */
    private void seedLinearWorkflow(String zoneCode, String procedureTitle, List<String> questions, String finalOutcome) {
        ProcedureZone zone = zoneRepository.findByCode(zoneCode).orElse(null);
        if (zone == null) return;

        Procedure procedure = procedureRepository.findByZoneOrderByTitleAsc(zone).stream()
                .filter(p -> p.getTitle().trim().equalsIgnoreCase(procedureTitle))
                .findFirst().orElse(null);
        if (procedure == null) return;

        if (!nodeRepository.findByProcedure(procedure).isEmpty()) return;

        ProcedureWorkflowNode next = null;
        for (int i = questions.size() - 1; i >= 0; i--) {
            ProcedureWorkflowNode node = nodeRepository.save(ProcedureWorkflowNode.builder()
                    .procedure(procedure).questionText(questions.get(i)).isStart(i == 0).build());
            if (next == null) {
                optionRepository.save(ProcedureWorkflowOption.builder()
                        .node(node).label("Terminé").outcome(finalOutcome).build());
            } else {
                optionRepository.save(ProcedureWorkflowOption.builder()
                        .node(node).label("Étape suivante").nextNode(next).build());
            }
            next = node;
        }
    }

    /**
     * Parcours interactif modèle — reproduit fidèlement la maquette "Consultation de solde"
     * fournie (5 étapes linéaires, alternance Agent/Client). Rattaché à la fiche
     * "COMPTE – DEMANDE DE SOLDE" déjà créée ci-dessus. Ne s'exécute qu'une fois — si
     * cette fiche a déjà des nœuds de parcours (créés ou modifiés depuis l'écran QA),
     * on ne touche à rien.
     */
    private void seedConsultationSoldeWorkflow() {
        ProcedureZone compteZone = zoneRepository.findByCode("COMPTE").orElse(null);
        if (compteZone == null) return;

        Procedure procedure = procedureRepository.findByZoneOrderByTitleAsc(compteZone).stream()
                .filter(p -> p.getTitle().trim().equalsIgnoreCase("COMPTE – DEMANDE DE SOLDE"))
                .findFirst().orElse(null);
        if (procedure == null) return;

        boolean alreadyHasWorkflow = !nodeRepository.findByProcedure(procedure).isEmpty();
        if (alreadyHasWorkflow) return;

        ProcedureWorkflowNode step5 = nodeRepository.save(ProcedureWorkflowNode.builder()
                .procedure(procedure).questionText("Clôturer l'incident").isStart(false).build());
        optionRepository.save(ProcedureWorkflowOption.builder()
                .node(step5).label("Incident clôturé").outcome("CLOTURE").build());

        ProcedureWorkflowNode step4 = nodeRepository.save(ProcedureWorkflowNode.builder()
                .procedure(procedure).questionText("Promouvoir le canal digital").isStart(false).build());
        optionRepository.save(ProcedureWorkflowOption.builder()
                .node(step4).label("Fait").nextNode(step5).build());

        ProcedureWorkflowNode step3 = nodeRepository.save(ProcedureWorkflowNode.builder()
                .procedure(procedure).questionText("Communiquer le solde").isStart(false).build());
        optionRepository.save(ProcedureWorkflowOption.builder()
                .node(step3).label("Solde communiqué").nextNode(step4).build());

        ProcedureWorkflowNode step2 = nodeRepository.save(ProcedureWorkflowNode.builder()
                .procedure(procedure).questionText("Authentifier le client").isStart(false).build());
        optionRepository.save(ProcedureWorkflowOption.builder()
                .node(step2).label("Client authentifié").nextNode(step3).build());

        ProcedureWorkflowNode step1 = nodeRepository.save(ProcedureWorkflowNode.builder()
                .procedure(procedure).questionText("Demander le nom, prénom et numéro de compte").isStart(true).build());
        optionRepository.save(ProcedureWorkflowOption.builder()
                .node(step1).label("Informations recueillies").nextNode(step2).build());

        log.warn("⚠ [PROCEDURE SEED] Parcours interactif modèle créé sur \"COMPTE – DEMANDE DE SOLDE\".");
    }
}

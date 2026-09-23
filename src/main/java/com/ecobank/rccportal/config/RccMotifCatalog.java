package com.ecobank.rccportal.config;

import java.util.List;

/**
 * Référentiel opérationnel RCC360 fourni par l'utilisateur (motifs de réclamation client,
 * hors demandes internes agent/QA déjà gérées par WorkflowService.ALLOWED_TYPES).
 *
 * Chaque motif porte un code utilisé comme "Type" de WorkflowRequest/SlaTarget, une équipe
 * affiliée principale (celle réellement en charge du traitement, quand plusieurs équipes sont
 * listées dans le document source), un niveau (N1/N2/Critique), un SLA en heures (converti
 * depuis le texte du référentiel — "jours ouvrés" traité comme jours calendaires faute de
 * distinction gérée par le système, voir note dans WorkflowSchemaBootstrap) et une priorité
 * (P1 à P4, voir classification RAF AI du référentiel).
 *
 * slaHours == null signifie "non défini/non précisé dans la procédure" dans le document source
 * — aucune ligne SlaTarget n'est alors semée pour ce motif, et le seuil global sert de repli.
 */
public final class RccMotifCatalog {

    public record MotifEntry(
            String thematique,
            String code,
            String label,
            String teamCode,
            String teamLabel,
            String niveau,
            Integer slaHours,
            String priority) {
    }

    private static final int JOUR = 24;

    public static final List<MotifEntry> ENTRIES = List.of(
            // ===== COMPTES & SERVICES =====
            new MotifEntry("Comptes & services", "DAT", "Dépôt à terme (DAT)", "BACK_OFFICE_DEPOTS", "Back Office Dépôts", "N2", null, "P3"),
            new MotifEntry("Comptes & services", "SOLDE_COMPTE", "Solde de compte", "RCC_FRONT_OFFICE", "RCC Front Office", "N1", 1, "P4"),
            new MotifEntry("Comptes & services", "RIB_SWIFT", "RIB / SWIFT", "DIGITAL_BANKING", "Digital Banking", "N1", JOUR, "P4"),
            new MotifEntry("Comptes & services", "OUVERTURE_COMPTE", "Ouverture de compte — nouvelle demande", "RESEAU_AGENCE", "Réseau Agence", "N1", null, "P3"),
            new MotifEntry("Comptes & services", "OUVERTURE_COMPTE_SUIVI", "Ouverture de compte — suivi", "RCC_RESOLUTION", "RCC Résolution", "N2", null, "P3"),
            new MotifEntry("Comptes & services", "LEVEE_RESTRICTION", "Levée de restriction — nouvelle demande", "COMPLIANCE", "Agence / Compliance", "N1", null, "P3"),
            new MotifEntry("Comptes & services", "LEVEE_RESTRICTION_SUIVI", "Levée de restriction — suivi", "RCC_RESOLUTION", "RCC Résolution", "N2", null, "P3"),
            new MotifEntry("Comptes & services", "MAJ_INFOS", "Mise à jour des informations — nouvelle demande", "KYC", "Agence / KYC", "N1", null, "P3"),
            new MotifEntry("Comptes & services", "MAJ_INFOS_SUIVI", "Mise à jour des informations — suivi", "RCC_RESOLUTION", "RCC Résolution", "N2", null, "P3"),

            // ===== VIREMENTS ET TRANSFERTS =====
            new MotifEntry("Virements & transferts", "ANNULATION_TRANSFERT", "Annulation de transfert", "BO_TRANSFERTS", "Back Office Transferts", "N2", null, "P3"),
            new MotifEntry("Virements & transferts", "MODIF_BENEFICIAIRE", "Modification bénéficiaire", "PAIEMENTS", "Paiements / Opérations", "N2", null, "P3"),
            new MotifEntry("Virements & transferts", "APPEL_FONDS", "Appel de fonds", "INTL_OPERATIONS", "International Operations", "N2", null, "P3"),

            // ===== DOCUMENTS BANCAIRES =====
            new MotifEntry("Documents bancaires", "ATTESTATION_BANCAIRE", "Attestation bancaire", "RCC_RESOLUTION", "RCC Résolution", "N2", 5 * JOUR, "P3"),
            new MotifEntry("Documents bancaires", "REFERENCE_BANCAIRE", "Référence bancaire", "RCC_RESOLUTION", "RCC Résolution", "N2", 5 * JOUR, "P3"),
            new MotifEntry("Documents bancaires", "RELEVE_ECI", "Relevé bancaire — client ECI", "DIGITAL_SOLICITATION", "Digital Solicitation", "N1", JOUR, "P4"),
            new MotifEntry("Documents bancaires", "RELEVE_AUTRE_FILIALE", "Relevé bancaire — autre filiale", "RCC_RESOLUTION", "RCC Résolution", "N2", 2 * JOUR, "P4"),

            // ===== ASSURANCE =====
            new MotifEntry("Assurance", "SOUSCRIPTION_ASSURANCE", "Souscription", "ECOBANK_ASSURANCE", "Ecobank Assurance", "N1", null, "P3"),
            new MotifEntry("Assurance", "RACHAT_ASSURANCE", "Rachat Assurance", "ECOBANK_ASSURANCE", "Ecobank Assurance", "N2", 5 * JOUR, "P3"),
            new MotifEntry("Assurance", "CONTESTATION_ASSURANCE", "Contestation prélèvement assurance", "ASSURANCE_FINANCE", "Assurance / Finance", "N2", 5 * JOUR, "P3"),

            // ===== CRÉDITS =====
            new MotifEntry("Crédits", "NOUVELLE_DEMANDE_PRET", "Nouvelle demande de prêt", "CREDIT_RETAIL", "Crédit Retail", "N1", 72, "P3"),
            new MotifEntry("Crédits", "RELANCE_DOSSIER_CREDIT", "Relance dossier crédit", "CREDIT_RETAIL", "Crédit Retail", "N2", null, "P3"),
            new MotifEntry("Crédits", "RACHAT_CREDIT", "Rachat de crédit", "CREDIT_RETAIL", "Crédit Retail", "N2", null, "P3"),
            new MotifEntry("Crédits", "CASH_COLLATERAL", "Cash Collateral", "CREDIT_GARANTIES", "Crédit / Garanties", "N2", null, "P3"),

            // ===== CARTES BANCAIRES =====
            new MotifEntry("Cartes bancaires", "CARTE_AVALEE_ECOBANK", "Carte avalée — GAB Ecobank", "AGENCE", "Agence", "N1", null, "P2"),
            new MotifEntry("Cartes bancaires", "CARTE_AVALEE_AUTRE_BANQUE", "Carte avalée — GAB autre banque", "CARD_SERVICES", "Card Services", "N1", null, "P2"),
            new MotifEntry("Cartes bancaires", "CARTE_PERDUE", "Carte perdue", "CARD_SERVICES", "Card Services / Fraud Risk", "N1", null, "P1"),
            new MotifEntry("Cartes bancaires", "CARTE_VOLEE", "Carte volée", "FRAUD_RISK", "Fraud Risk / Card Services", "N1", null, "P1"),
            new MotifEntry("Cartes bancaires", "DEBLOCAGE_CARTE", "Déblocage carte", "CARD_SERVICES", "Card Services", "N1", null, "P2"),
            new MotifEntry("Cartes bancaires", "NON_RECEPTION_CARTE", "Non réception de carte", "CARD_SERVICES", "Card Services / RCC Résolution", "N2", 30 * JOUR, "P3"),
            new MotifEntry("Cartes bancaires", "NON_RECEPTION_PIN", "Non réception PIN", "CARD_SERVICES", "Card Services", "N2", null, "P3"),

            // ===== INCIDENTS MONÉTIQUES =====
            new MotifEntry("Incidents monétiques", "DEBIT_SANS_DISPENSE", "Débit sans dispense", "MONETIQUE", "Monétique / Card Operations", "N2", 2 * JOUR, "P2"),
            new MotifEntry("Incidents monétiques", "RETRAIT_PARTIEL", "Retrait partiellement servi", "MONETIQUE", "Monétique", "N2", 5 * JOUR, "P2"),
            new MotifEntry("Incidents monétiques", "RETRAIT_NON_SERVI", "Retrait non servi", "MONETIQUE", "Monétique / Contestation", "N2", 30 * JOUR, "P2"),
            new MotifEntry("Incidents monétiques", "TRANSACTION_FRAUDULEUSE", "Transaction frauduleuse", "FRAUD_RISK", "Fraud Risk / Sécurité", "Critique", null, "P1"),

            // ===== ECOBANK ONLINE =====
            new MotifEntry("Ecobank Online", "EOL_CREATION_PROFIL", "Création profil", "DIGITAL_BANKING", "Digital Banking", "N1", null, "P4"),
            new MotifEntry("Ecobank Online", "EOL_ACTIVATION_VIREMENTS", "Activation virements", "DIGITAL_BANKING", "Digital Banking / Agence", "N2", null, "P3"),
            new MotifEntry("Ecobank Online", "EOL_MDP_OUBLIE", "Mot de passe oublié", "DIGITAL_BANKING", "Digital Banking", "N1", null, "P4"),
            new MotifEntry("Ecobank Online", "EOL_COMPTE_BLOQUE", "Compte bloqué", "DIGITAL_BANKING", "Digital Banking", "N1", null, "P3"),

            // ===== MOBILE APP =====
            new MotifEntry("Mobile App", "MAPP_CREATION_PROFIL", "Création profil", "MOBILE_BANKING", "Mobile Banking", "N1", null, "P4"),
            new MotifEntry("Mobile App", "MAPP_REINIT_PROFIL", "Réinitialisation profil", "MOBILE_BANKING", "Mobile Banking", "N1", null, "P4"),
            new MotifEntry("Mobile App", "MAPP_AJOUT_COMPTE", "Ajout compte", "MOBILE_BANKING", "Mobile Banking", "N1", null, "P4"),
            new MotifEntry("Mobile App", "MAPP_INCIDENT", "Incident Mobile App", "MOBILE_BANKING", "Mobile Banking / RCC Résolution", "N2", null, "P3"),

            // ===== MOBILE MONEY =====
            new MotifEntry("Mobile Money", "MMONEY_LIAISON_COMPTE", "Liaison compte", "MOBILE_MONEY", "Digital Banking / Mobile Money", "N1", null, "P4"),
            new MotifEntry("Mobile Money", "MMONEY_RECLAMATION", "Réclamation Mobile Money", "MOBILE_MONEY_SUPPORT", "Mobile Money Support", "N2", 5 * JOUR, "P3"),
            new MotifEntry("Mobile Money", "MMONEY_REVERSE", "Reverse Mobile Money", "MOBILE_MONEY_OPS", "Mobile Money Operations", null, 72, "P2"),

            // ===== CASHXPRESS =====
            new MotifEntry("CashXpress", "CXP_RECLAMATION", "Réclamation transaction", "GTP_LIMITED", "GTP Limited / Card Operations", "N2", 45 * JOUR, "P3"),
            new MotifEntry("CashXpress", "CXP_BLOCAGE_CARTE", "Blocage carte", "GTP_LIMITED", "GTP Limited", "N1", null, "P2"),

            // ===== CHÈQUES =====
            new MotifEntry("Chèques", "OPPOSITION_CHEQUE", "Opposition chèque", "OPERATIONS", "Operations / Agence", "N2", null, "P2"),
            new MotifEntry("Chèques", "LEVEE_OPPOSITION_CHEQUE", "Levée opposition", "AGENCE", "Agence", "N1", null, "P3"),
            new MotifEntry("Chèques", "CHEQUE_REJETE", "Chèque rejeté", "COMPENSATION", "Compensation", "N1", null, "P3"),
            new MotifEntry("Chèques", "SUIVI_REMISE_CHEQUE", "Suivi remise de chèque", "COMPENSATION", "Compensation / RCC Résolution", "N2", null, "P3"),

            // ===== PLAINTES CLIENTS =====
            new MotifEntry("Plaintes clients", "RECLAMATION_QUALITE", "Réclamation qualité", "QA", "Quality Assurance / RCC Management", "N2", null, "P3"),
            new MotifEntry("Plaintes clients", "ESCALADE_DIRECTION", "Escalade direction", "RCC_MANAGER", "RCC Manager / Customer Experience", "N1", null, "P1")
    );

    private RccMotifCatalog() {
    }

    public static java.util.Optional<MotifEntry> findByCode(String code) {
        if (code == null) return java.util.Optional.empty();
        return ENTRIES.stream().filter(e -> e.code().equalsIgnoreCase(code)).findFirst();
    }
}

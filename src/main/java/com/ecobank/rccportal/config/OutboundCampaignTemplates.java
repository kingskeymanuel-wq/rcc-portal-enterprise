package com.ecobank.rccportal.config;

/**
 * Modèles de campagnes Outbound au même format que ceux préparés de longue date dans
 * WorkflowSchemaBootstrap (Prêt Scolaire & Conso 2026, Carte Bancaire, Offres Outbound
 * Générales) : identifiants explicites (interesse, sinonPourquoi, quandContacter…), listes de
 * choix Oui / Non / Besoin de réfléchir et listes de motifs.
 *
 * « Réactivation des comptes dormants » reprend le formulaire Microsoft Forms utilisé par
 * l'équipe ; ses listes de motifs et d'agences sont tirées des réponses réellement saisies
 * (export de 5 000 appels, avril–août 2026) pour que l'import range chaque réponse libre dans
 * la bonne option (voir CampaignService.matchOption).
 */
public final class OutboundCampaignTemplates {

    private OutboundCampaignTemplates() {
    }

    public static final String DORMANT_NAME = "Réactivation des comptes dormants";

    public static final String DORMANT_DESCRIPTION = "Appeler les clients dont le compte est resté plusieurs mois sans mouvement : "
            + "comprendre la raison, proposer la réactivation, le package, la carte ou la migration, et fixer un passage en agence.";

    private static final String YES_NO = "[\"Oui\",\"Non\",\"Besoin de réfléchir\"]";

    public static final String DORMANT_FIELDS_JSON = "["
            + "{\"id\":\"raisonInactivite\",\"label\":\"Votre compte est resté plusieurs mois sans mouvements : quelles en sont les raisons ?\","
            + "\"type\":\"SELECT\",\"required\":false,\"options\":["
            + "\"Difficultés financières\",\"Clôture du compte demandée\",\"Oubli\",\"Client en voyage\",\"Services ne marchent pas\","
            + "\"Client plus en activité\",\"Client au chômage / emploi perdu\",\"Plus intéressé par le compte\","
            + "\"Client non satisfait - risque de clôture\",\"Compte boursier\",\"Arrêt de paiement de la bourse\",\"Agence fermée\","
            + "\"Maladie\",\"Le client souhaite réfléchir\",\"A changé de banque\",\"Salaire domicilié dans une autre banque\","
            + "\"Compte de dépôt de garantie\",\"Compte entreprise / marchand\",\"Ne reconnaît pas le compte\",\"Prélèvements contestés\","
            + "\"Décès du titulaire\",\"Défaut de CNI\",\"En attente d'un virement\",\"Aucune raison\",\"Autre\"]},"
            + "{\"id\":\"interesse\",\"label\":\"Le client est-il intéressé par la réactivation du compte ?\",\"type\":\"SELECT\",\"required\":true,\"options\":" + YES_NO + "},"
            + "{\"id\":\"interessePackage\",\"label\":\"Proposez le package. Le client est-il intéressé ?\",\"type\":\"SELECT\",\"required\":false,\"options\":" + YES_NO + "},"
            + "{\"id\":\"interesseCarte\",\"label\":\"Proposez la carte. Le client est-il intéressé ?\",\"type\":\"SELECT\",\"required\":false,\"options\":" + YES_NO + "},"
            + "{\"id\":\"interesseMigration\",\"label\":\"Proposez la migration de compte. Le client est-il intéressé ?\",\"type\":\"SELECT\",\"required\":false,\"options\":" + YES_NO + "},"
            + "{\"id\":\"sinonPourquoi\",\"label\":\"Si non, pourquoi ?\",\"type\":\"SELECT\",\"required\":false,\"options\":["
            + "\"Souhaite clôturer son compte\",\"Souhaite clôturer et ouvrir un compte épargne\",\"Plus intéressé par le compte\","
            + "\"Compte entreprise\",\"Compte marchand\",\"Compte évolution\",\"Compte épargne\",\"Compte de dépôt de garantie\","
            + "\"Client plus en activité\",\"Client au chômage\",\"Difficultés financières\",\"A changé de banque\","
            + "\"Prélèvements injustifiés\",\"Ne reconnaît pas le compte\",\"Effectue déjà des versements\",\"Maladie\",\"Autre\"]},"
            + "{\"id\":\"agenceRdv\",\"label\":\"Agence de RDV\",\"type\":\"SELECT\",\"required\":false,\"options\":["
            + "\"Siège\",\"Caistab (agence principale)\",\"Marcory Marché\",\"Koumassi\",\"Treichville Marché\",\"Adjamé\",\"Djibi\","
            + "\"Cité des Arts\",\"Aghien\",\"Niangon\",\"St Pierre des Rosées\",\"Bel Air\",\"Vallon\",\"Kouté\",\"Dokui\",\"Abatta\","
            + "\"Zone 3\",\"Zone 4\",\"Kokomall\",\"Bassam\",\"Aboisso\",\"Adzopé\",\"Abengourou\",\"Bondoukou\",\"Yamoussoukro\",\"Bouaké\","
            + "\"Daloa\",\"Duékoué\",\"Man\",\"Gagnoa\",\"Soubré\",\"San-Pédro\",\"Korhogo\",\"Autre\"]},"
            + "{\"id\":\"datePassageAgence\",\"label\":\"Quand souhaitez-vous passer en agence ?\",\"type\":\"DATE\",\"required\":false,\"options\":[]},"
            + "{\"id\":\"quandContacter\",\"label\":\"Quand souhaitez-vous être recontacté ?\",\"type\":\"DATE\",\"required\":false,\"options\":[]},"
            + "{\"id\":\"commentaire\",\"label\":\"Commentaire libre\",\"type\":\"TEXTAREA\",\"required\":false,\"options\":[]}"
            + "]";

    /** Identifiants de la première version (v188) → identifiants du modèle. */
    public static final String[][] DORMANT_V188_IDS = {
            {"r10", "commentaire"}, {"r1", "raisonInactivite"}, {"r2", "interesse"}, {"r3", "interessePackage"},
            {"r4", "interesseCarte"}, {"r5", "interesseMigration"}, {"r6", "sinonPourquoi"}, {"r7", "agenceRdv"},
            {"r8", "datePassageAgence"}, {"r9", "quandContacter"}};
}

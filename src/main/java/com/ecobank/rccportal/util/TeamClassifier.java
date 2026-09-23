package com.ecobank.rccportal.util;

/**
 * Classifie le champ libre User.activity (très hétérogène en pratique — "INBOUND VOICE",
 * "INBOUND MAIL/CIS", "CMB CIB", "OUTBOUND", "RESOLUTION"...) vers l'une des 4 équipes
 * canoniques du Portail Team Leader / tableau de bord Outbound. Utilisé côté backend
 * (filtrage Reporting/QA/présence par équipe menée) et exposé côté frontend via
 * TeamStatusResponse pour la redirection après connexion.
 *
 * ⚠ Correspondance par mots-clés, pas de table de référence figée — les libellés d'équipe
 * réels varient selon comment chaque roster a été importé. Un agent dont l'activité ne
 * correspond à aucun mot-clé connu reste classé OTHER (aucune redirection spécifique,
 * aucun rattachement à un Team Leader tant que l'admin n'aligne pas les libellés).
 */
public final class TeamClassifier {

    public enum Team {
        INBOUND_VOICE("Inbound Voix"),
        INBOUND_MAIL("Inbound Mail / Rafiki"),
        CIB("CIB"),
        OUTBOUND("Outbound"),
        OTHER("Autre / non classée");

        public final String label;
        Team(String label) { this.label = label; }
    }

    private TeamClassifier() {
    }

    public static Team classify(String activity) {
        if (activity == null || activity.isBlank()) return Team.OTHER;
        String a = activity.toUpperCase();

        if (a.contains("OUTBOUND") || a.contains("TELEVENDEUR") || a.contains("TÉLÉVENDEUR")
                || a.contains("TELEVENTE") || a.contains("TÉLÉVENTE") || a.contains("DIGITAL")) return Team.OUTBOUND;
        if (a.contains("CIB")) return Team.CIB;
        if (a.contains("MAIL") || a.contains("TCHAT") || a.contains("CHAT") || a.contains("RESOLUTION") || a.contains("RAFIKI")) return Team.INBOUND_MAIL;
        if (a.contains("VOICE") || a.contains("VOIX") || a.contains("CONSEILLER") || a.contains("INBOUND")) return Team.INBOUND_VOICE;

        return Team.OTHER;
    }
}

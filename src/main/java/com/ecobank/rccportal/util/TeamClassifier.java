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

    /** Équipe d'un utilisateur : champ Activité, sinon déduite de ses codes de service (AGENT_INBOUND → Inbound Voix…). */
    public static Team classify(String activity, java.util.Collection<String> serviceCodes) {
        Team team = classify(activity);
        if (team != Team.OTHER || serviceCodes == null) return team;
        for (String code : serviceCodes) {
            if (code == null) continue;
            Team t = classify(code.replace('_', ' '));
            if (t != Team.OTHER) return t;
        }
        return team;
    }

    // ───────────── Canaux digitaux de l'Inbound Mail : Tchat et Rafiki ─────────────

    /** Codes d'équipe menée (User.ledTeam) propres à un canal : un Team Leader Tchat ou Rafiki. */
    public static final java.util.Set<String> CHANNELS = java.util.Set.of("TCHAT", "RAFIKI");

    public static boolean isChannel(String code) {
        return code != null && CHANNELS.contains(code.trim().toUpperCase());
    }

    /** Canal d'un agent : « RAFIKI » ou « TCHAT » (activité ou service AGENT_TCHAT / AGENT_RAFIKI), sinon null. */
    public static String channel(String activity, java.util.Collection<String> serviceCodes) {
        String a = activity == null ? "" : activity.toUpperCase();
        if (a.contains("RAFIKI")) return "RAFIKI";
        if (a.contains("TCHAT") || a.contains("CHAT")) return "TCHAT";
        if (serviceCodes != null) {
            for (String c : serviceCodes) {
                if (c == null) continue;
                String u = c.toUpperCase();
                if (u.contains("RAFIKI")) return "RAFIKI";
                if (u.contains("TCHAT")) return "TCHAT";
            }
        }
        return null;
    }

    /** Équipe du reporting d'un code d'équipe menée : TCHAT / RAFIKI relèvent de l'Inbound Mail. */
    public static Team teamOf(String ledTeam) {
        if (ledTeam == null || ledTeam.isBlank()) return Team.OTHER;
        String code = ledTeam.trim().toUpperCase().replace(' ', '_');
        if (CHANNELS.contains(code)) return Team.INBOUND_MAIL;
        try {
            return Team.valueOf(code);
        } catch (IllegalArgumentException e) {
            return classify(ledTeam);
        }
    }

    /** L'agent fait-il partie de l'équipe menée ? Un Team Leader Tchat ne voit que le Tchat ; celui de l'Inbound Mail voit tout le pôle. */
    public static boolean belongsTo(String ledTeam, String activity, java.util.Collection<String> serviceCodes) {
        Team team = teamOf(ledTeam);
        if (team == Team.OTHER || classify(activity, serviceCodes) != team) return false;
        return !isChannel(ledTeam) || ledTeam.trim().equalsIgnoreCase(channel(activity, serviceCodes));
    }

    /** Équipes menées pouvant encadrer cet agent, de la plus précise à la plus large (TCHAT puis INBOUND_MAIL). */
    public static java.util.List<String> leaderCodesFor(String activity, java.util.Collection<String> serviceCodes) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Team team = classify(activity, serviceCodes);
        String ch = team == Team.INBOUND_MAIL ? channel(activity, serviceCodes) : null;
        if (ch != null) out.add(ch);
        if (team != Team.OTHER) out.add(team.name());
        return out;
    }

    /** L'agent (champ Activité libre) relève-t-il de cette équipe (code d'équipe ou libellé d'activité) ? */
    public static boolean matchesTeam(String team, String activity) {
        if (team == null || team.isBlank()) return false;
        if (team.trim().equalsIgnoreCase(activity == null ? "" : activity.trim())) return true;
        return belongsTo(team, activity, null);
    }

    /** Team Leader d'un agent parmi des candidats : celui de son canal (Tchat, Rafiki) d'abord, puis celui de son pôle. */
    public static <U> U leaderFor(String activity, java.util.Collection<U> leaders, java.util.function.Function<U, String> ledTeam) {
        java.util.List<String> codes = new java.util.ArrayList<>(leaderCodesFor(activity, null));
        if (activity != null && !activity.isBlank()) codes.add(activity.trim());
        for (String code : codes) {
            for (U u : leaders) {
                String led = ledTeam.apply(u);
                if (led != null && led.trim().replace(' ', '_').equalsIgnoreCase(code.replace(' ', '_'))) return u;
            }
        }
        return null;
    }
}

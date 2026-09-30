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
        if (a.contains("MAIL") || a.contains("TCHAT") || a.contains("CHAT") || a.contains("RESEAU") || a.contains("RÉSEAU")
                || a.contains("RESOLUTION") || a.contains("RAFIKI")) return Team.INBOUND_MAIL;
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

    // ───────────── Sous-équipes : Tchat et Rafiki (Inbound Mail), Télévente et Digitalisation (Outbound) ─────────────

    /** Codes d'équipe menée (User.ledTeam) propres à une sous-équipe, chacune avec son Team Leader et son portail. */
    public static final java.util.Set<String> CHANNELS = java.util.Set.of("TCHAT", "RAFIKI", "TELEVENTE", "DIGITALISATION");

    /** Pôle de chaque sous-équipe (reporting, droits du Team Leader de tout le pôle). */
    public static final java.util.Map<String, Team> CHANNEL_TEAM = java.util.Map.of(
            "TCHAT", Team.INBOUND_MAIL, "RAFIKI", Team.INBOUND_MAIL,
            "TELEVENTE", Team.OUTBOUND, "DIGITALISATION", Team.OUTBOUND);

    /** Sous-équipe d'après un libellé (activité ou code de service), null si aucune. */
    static String channelOf(String text) {
        if (text == null) return null;
        String a = text.toUpperCase();
        if (a.contains("RAFIKI")) return "RAFIKI";
        if (a.contains("TELEVENTE") || a.contains("TÉLÉVENTE") || a.contains("TELEVENDEUR") || a.contains("TÉLÉVENDEUR")) return "TELEVENTE";
        if (a.contains("DIGITAL")) return "DIGITALISATION";
        if (a.contains("TCHAT") || a.contains("CHAT") || a.contains("RESEAU") || a.contains("RÉSEAU")) return "TCHAT";
        return null;
    }

    public static boolean isChannel(String code) {
        return code != null && CHANNELS.contains(code.trim().toUpperCase());
    }

    /**
     * Sous-équipe d'un agent : « RAFIKI », « TCHAT », « TELEVENTE » ou « DIGITALISATION » (activité, sinon service
     * AGENT_TCHAT / AGENT_RAFIKI / AGENT_TELEVENTE / AGENT_DIGITALISATION), null sinon.
     */
    public static String channel(String activity, java.util.Collection<String> serviceCodes) {
        String ch = channelOf(activity);
        if (ch != null) return ch;
        if (serviceCodes != null) {
            for (String c : serviceCodes) {
                String u = c == null ? "" : c.toUpperCase();
                if (!u.startsWith("AGENT_") && !u.contains("RAFIKI") && !u.contains("TCHAT")) continue;
                ch = channelOf(u.replace('_', ' '));
                if (ch != null) return ch;
            }
        }
        return null;
    }

    /** Équipe du reporting d'un code d'équipe menée : TCHAT / RAFIKI relèvent de l'Inbound Mail, TELEVENTE / DIGITALISATION de l'Outbound. */
    public static Team teamOf(String ledTeam) {
        if (ledTeam == null || ledTeam.isBlank()) return Team.OTHER;
        String code = ledTeam.trim().toUpperCase().replace(' ', '_');
        if (CHANNELS.contains(code)) return CHANNEL_TEAM.get(code);
        try {
            return Team.valueOf(code);
        } catch (IllegalArgumentException e) {
            return classify(ledTeam);
        }
    }

    /** L'agent fait-il partie de l'équipe menée ? Un Team Leader Tchat (ou Télévente) ne voit que sa sous-équipe ; celui du pôle voit tout le pôle. */
    public static boolean belongsTo(String ledTeam, String activity, java.util.Collection<String> serviceCodes) {
        Team team = teamOf(ledTeam);
        if (team == Team.OTHER || classify(activity, serviceCodes) != team) return false;
        return !isChannel(ledTeam) || ledTeam.trim().equalsIgnoreCase(channel(activity, serviceCodes));
    }

    /** Équipes menées pouvant encadrer cet agent, de la plus précise à la plus large (TCHAT puis INBOUND_MAIL). */
    public static java.util.List<String> leaderCodesFor(String activity, java.util.Collection<String> serviceCodes) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Team team = classify(activity, serviceCodes);
        String ch = channel(activity, serviceCodes);
        if (ch != null && CHANNEL_TEAM.get(ch) == team) out.add(ch);
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

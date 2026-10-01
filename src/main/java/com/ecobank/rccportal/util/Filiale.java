package com.ecobank.rccportal.util;

import com.ecobank.rccportal.security.AuthenticatedUser;

import java.util.Locale;
import java.util.Map;

/**
 * Filiale active de la requête — RCC ECI (Côte d'Ivoire, « CI ») ou RCC ETG (Togo, « TG ») — choisie par le Superviseur,
 * le RH, l'administrateur ou le Head QA dans l'en-tête du portail (en-tête HTTP X-RCC-Filiale). Les écrans de pilotage
 * (tableau de bord, reporting, performances, QA, planning, présence, ventes) ne montrent alors que cette filiale.
 * Sans choix, ou pour un autre profil, rien n'est filtré (comportement d'origine).
 */
public final class Filiale {

    private Filiale() {}

    public static final String HEADER = "X-RCC-Filiale";

    /** Nom des filiales dans tout le portail. */
    public static final Map<String, String> LABELS = Map.of("CI", "RCC ECI", "TG", "RCC ETG");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static String label(String country) {
        String c = country == null ? null : Affiliates.countryOf(country);
        return c == null ? "" : LABELS.getOrDefault(c, c);
    }

    /** Profils qui basculent d'une filiale à l'autre. */
    public static boolean canSwitch(AuthenticatedUser u) {
        if (u == null) return false;
        String role = u.role() == null ? "" : u.role().toUpperCase(Locale.ROOT);
        String service = u.service() == null ? "" : u.service().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return role.equals("ADMIN") || role.equals("SUPERVISOR") || role.equals("RH") || role.equals("QA_SUPERVISOR")
                || service.equals("SUPERVISEUR_QA") || service.equals("SUPERVISEUR");
    }

    /** Pose la filiale de la requête (valeur de l'en-tête), seulement pour un profil autorisé et une filiale connue. */
    public static void set(AuthenticatedUser user, String headerValue) {
        CURRENT.remove();
        if (headerValue == null || !canSwitch(user)) return;
        String c = headerValue.trim().toUpperCase(Locale.ROOT);
        if (LABELS.containsKey(c)) CURRENT.set(c);
    }

    public static void clear() { CURRENT.remove(); }

    /** Filiale propre au compte (Team Leader) : son équipe, son planning et son reporting restent dans sa filiale. */
    public static void setOwn(String country) {
        String c = country == null ? null : Affiliates.countryOf(country);
        if (c != null && LABELS.containsKey(c)) CURRENT.set(c);
    }

    /** Filiale active, null = toutes. */
    public static String current() { return CURRENT.get(); }

    /** Filiale demandée explicitement, sinon la filiale active. */
    public static String orCurrent(String explicit) {
        return explicit != null && !explicit.isBlank() ? explicit : current();
    }

    /** La personne (code AFFILIATE_BRANCH) fait-elle partie de la filiale active ? Toujours vrai sans filiale active. */
    public static boolean matches(String affiliateBranch) {
        String c = current();
        return c == null || c.equals(Affiliates.countryOf(affiliateBranch));
    }

    /**
     * Condition SQL sur la colonne AFFILIATE_BRANCH de l'alias donné ("u") pour la filiale active — chaîne vide sans filiale.
     * Mêmes règles que Affiliates.countryOf : vide et K01 = Côte d'Ivoire.
     */
    public static String sql(String alias) {
        String c = current();
        if (c == null) return "";
        String col = alias + ".AFFILIATE_BRANCH";
        if ("TG".equals(c)) return " AND (UPPER(LTRIM(RTRIM(" + col + "))) IN ('TG', 'TGO') OR UPPER(" + col + ") LIKE '%TOGO%')";
        return " AND (" + col + " IS NULL OR LTRIM(RTRIM(" + col + ")) = '' OR UPPER(LTRIM(RTRIM(" + col + "))) IN ('CI', 'K01', 'CIV') OR UPPER(" + col + ") LIKE '%IVOIRE%')";
    }
}

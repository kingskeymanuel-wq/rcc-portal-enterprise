package com.ecobank.rccportal.util;

import java.util.Locale;
import java.util.Map;

/**
 * Filiale (code pays ISO à 2 lettres) d'un code AFFILIATE_BRANCH de dbo.USERS.
 * Les comptes importés portent le code d'agence Ecobank (« K01 » = Côte d'Ivoire) plutôt que le code pays :
 * c'est ici, et seulement ici, qu'on fait la correspondance — un nouveau code se déclare dans BRANCH_CODES.
 */
public final class Affiliates {

    /** Codes d'agence / d'affiliée connus → pays. */
    public static final Map<String, String> BRANCH_CODES = Map.of(
            "K01", "CI",
            "CIV", "CI",
            "TGO", "TG");

    private Affiliates() {
    }

    /** « K01 », « CI », « Côte d'Ivoire » → « CI » ; « TG », « Togo » → « TG » ; vide → « CI » (siège RCC). */
    public static String countryOf(String branch) {
        if (branch == null || branch.isBlank()) return "CI";
        String b = branch.trim().toUpperCase(Locale.ROOT);
        String known = BRANCH_CODES.get(b);
        if (known != null) return known;
        if (b.startsWith("CI") || b.contains("IVOIRE")) return "CI";
        if (b.startsWith("TG") || b.contains("TOGO")) return "TG";
        return b.length() > 2 ? b.substring(0, 2) : b;
    }

    /** Même filiale ? (« K01 » et « CI » : oui). */
    public static boolean sameCountry(String a, String b) {
        return countryOf(a).equals(countryOf(b));
    }
}

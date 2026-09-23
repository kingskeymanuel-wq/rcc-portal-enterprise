package com.ecobank.rccportal.service;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Masque les données à caractère sensible (numéros de compte, emails, téléphones) avant
 * tout envoi d'une question ou d'un contexte vers un service externe au périmètre Ecobank
 * (recherche web, Copilot Studio si hébergé hors tenant Ecobank, Anthropic Claude).
 *
 * Principe : jamais de donnée client identifiable envoyée à un moteur IA tiers ou à un
 * moteur de recherche public. Le masquage reste best-effort (expressions régulières) — ce
 * n'est pas une garantie absolue de conformité, mais une protection systématique de premier
 * niveau appliquée avant toute sortie du périmètre interne.
 */
@Service
public class DataProtectionService {

    // Sépare un numéro de compte/carte (10 à 20 chiffres consécutifs) d'un numéro de
    // téléphone plus court, pour ne pas les confondre lors du masquage.
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\b\\d{10,20}\\b");
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s.-]{7,14}\\d");

    /**
     * Masque les données sensibles détectées dans le texte. Retourne une chaîne vide pour
     * une entrée nulle plutôt que de lever une exception — un appelant qui masque avant
     * d'envoyer ne devrait jamais planter sur une valeur absente.
     */
    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String result = text;
        result = ACCOUNT_NUMBER.matcher(result).replaceAll("[NUMERO_COMPTE]");
        result = EMAIL.matcher(result).replaceAll("[EMAIL]");
        result = PHONE.matcher(result).replaceAll("[TELEPHONE]");
        return result;
    }

    /** True si le texte contient au moins une donnée jugée sensible — utile pour l'audit. */
    public boolean containsSensitiveData(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return ACCOUNT_NUMBER.matcher(text).find()
                || EMAIL.matcher(text).find()
                || PHONE.matcher(text).find();
    }
}

package com.ecobank.rccportal.service;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Glossaire hors-ligne des formules courantes de la relation client (français ↔ anglais).
 * Utilisé seulement en dernier recours, quand aucune source de traduction ne répond :
 * l'agent obtient au moins « bonjour », « merci »… au lieu d'une erreur. Correspondance
 * exacte uniquement (après normalisation) — jamais de traduction approximative.
 */
final class CommonPhrases {

    private static final String[][] FR_EN = {
            {"bonjour", "Hello"},
            {"bonsoir", "Good evening"},
            {"salut", "Hi"},
            {"au revoir", "Goodbye"},
            {"merci", "Thank you"},
            {"merci beaucoup", "Thank you very much"},
            {"s'il vous plait", "Please"},
            {"oui", "Yes"},
            {"non", "No"},
            {"d'accord", "Okay"},
            {"excusez-moi", "Excuse me"},
            {"desole", "Sorry"},
            {"je suis desole", "I am sorry"},
            {"bienvenue", "Welcome"},
            {"bonne journee", "Have a nice day"},
            {"bonne soiree", "Have a nice evening"},
            {"comment allez-vous", "How are you?"},
            {"comment puis-je vous aider", "How can I help you?"},
            {"en quoi puis-je vous aider", "How can I help you?"},
            {"un instant s'il vous plait", "One moment, please"},
            {"veuillez patienter", "Please hold on"},
            {"merci de votre patience", "Thank you for your patience"},
            {"merci pour votre patience", "Thank you for your patience"},
            {"merci de votre appel", "Thank you for your call"},
            {"merci d'avoir appele ecobank", "Thank you for calling Ecobank"},
            {"bienvenue chez ecobank", "Welcome to Ecobank"},
            {"votre carte est prete", "Your card is ready"},
            {"quel est votre numero de compte", "What is your account number?"},
            {"quel est votre nom", "What is your name?"},
            {"pouvez-vous repeter", "Could you repeat that?"},
            {"je vous transfere", "I am transferring you"},
            {"avez-vous d'autres questions", "Do you have any other questions?"},
            {"y a-t-il autre chose", "Is there anything else?"},
            {"je comprends", "I understand"},
            {"nous allons traiter votre demande", "We will process your request"},
    };

    private static final Map<String, String> TABLE = new HashMap<>();

    static {
        for (String[] p : FR_EN) {
            TABLE.put("fr|en|" + norm(p[0]), p[1]);
            TABLE.putIfAbsent("en|fr|" + norm(p[1]), capitalize(p[0]));
        }
        // Accents perdus par la normalisation, restitués pour l'affichage.
        TABLE.put("en|fr|" + norm("Please"), "S'il vous plaît");
        TABLE.put("en|fr|" + norm("Sorry"), "Désolé");
        TABLE.put("en|fr|" + norm("I am sorry"), "Je suis désolé");
        TABLE.put("en|fr|" + norm("Have a nice day"), "Bonne journée");
        TABLE.put("en|fr|" + norm("Have a nice evening"), "Bonne soirée");
        TABLE.put("en|fr|" + norm("One moment, please"), "Un instant, s'il vous plaît");
        TABLE.put("en|fr|" + norm("Your card is ready"), "Votre carte est prête");
        TABLE.put("en|fr|" + norm("What is your account number?"), "Quel est votre numéro de compte ?");
        TABLE.put("en|fr|" + norm("Could you repeat that?"), "Pouvez-vous répéter ?");
        TABLE.put("en|fr|" + norm("I am transferring you"), "Je vous transfère");
        TABLE.put("en|fr|" + norm("Thank you for calling Ecobank"), "Merci d'avoir appelé Ecobank");
        TABLE.put("en|fr|" + norm("We will process your request"), "Nous allons traiter votre demande");
        TABLE.put("en|fr|" + norm("Hi"), "Salut");
        TABLE.put("en|fr|" + norm("Thanks"), "Merci");
    }

    private CommonPhrases() {
    }

    static String lookup(String text, String source, String target) {
        if (text == null || source == null || target == null) return null;
        String s = base(source);
        String t = base(target);
        if (s.equals(t)) return null;
        String hit = TABLE.get(s + "|" + t + "|" + norm(text));
        return hit == null || hit.isEmpty() ? null : hit;
    }

    private static String base(String lang) {
        String l = lang.toLowerCase(Locale.ROOT);
        int dash = l.indexOf('-');
        return dash > 0 ? l.substring(0, dash) : l;
    }

    static String norm(String text) {
        String n = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[\\s\\u00A0]+", " ")
                .replaceAll("[\\s.,;:!?¡¿]+$", "").replaceAll("^[\\s¡¿]+", "").trim();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

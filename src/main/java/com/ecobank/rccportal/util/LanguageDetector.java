package com.ecobank.rccportal.util;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Détection de langue locale, sans dépendance ni appel réseau — utilisée par le Traducteur
 * quand l'agent laisse « Détecter la langue » et que la source de traduction disponible ne
 * sait pas détecter elle-même (MyMemory, Argos sans langdetect). Avant ce correctif, le choix
 * par défaut « Détecter la langue » échouait systématiquement avec MyMemory.
 *
 * <p>Deux étapes : (1) l'alphabet (arabe, cyrillique, CJK, grec, hébreu, thaï, devanagari,
 * éthiopien) suffit à trancher ; (2) pour l'alphabet latin, score de mots-outils très
 * fréquents par langue. Retourne {@code null} quand le texte est trop court ou ambigu —
 * l'appelant doit alors demander à l'agent de préciser la langue plutôt que de deviner.</p>
 */
public final class LanguageDetector {

    private static final Map<String, Set<String>> FUNCTION_WORDS = new LinkedHashMap<>();

    static {
        FUNCTION_WORDS.put("fr", Set.of("le", "la", "les", "des", "une", "est", "et", "vous", "nous", "que", "qui",
                "pour", "dans", "pas", "sur", "avec", "je", "votre", "sont", "mais", "ce", "cette", "au", "aux",
                "du", "il", "elle", "merci", "bonjour", "ne", "tres", "mon", "compte", "carte"));
        FUNCTION_WORDS.put("en", Set.of("the", "and", "is", "are", "you", "your", "to", "of", "in", "for", "with",
                "this", "that", "have", "has", "not", "we", "our", "please", "thank", "hello", "it", "be", "was",
                "will", "can", "my", "account", "card"));
        FUNCTION_WORDS.put("es", Set.of("el", "los", "las", "una", "es", "y", "usted", "que", "por", "para", "con",
                "no", "su", "del", "al", "como", "muy", "gracias", "hola", "cuenta", "tarjeta", "pero", "esta"));
        FUNCTION_WORDS.put("pt", Set.of("o", "os", "as", "um", "uma", "e", "voce", "que", "para", "com", "nao",
                "seu", "sua", "do", "da", "dos", "das", "obrigado", "ola", "conta", "cartao", "mas", "esta", "em"));
        FUNCTION_WORDS.put("de", Set.of("der", "die", "das", "und", "ist", "sie", "nicht", "mit", "fur", "auf",
                "ein", "eine", "ich", "wir", "ihr", "ihre", "danke", "hallo", "konto", "karte", "aber", "zu"));
        FUNCTION_WORDS.put("it", Set.of("il", "lo", "gli", "una", "e", "che", "per", "con", "non", "sono", "del",
                "della", "grazie", "ciao", "conto", "carta", "ma", "questo", "suo"));
        FUNCTION_WORDS.put("nl", Set.of("de", "het", "een", "en", "is", "niet", "met", "voor", "van", "ik", "wij",
                "u", "uw", "dank", "hallo", "rekening", "kaart", "maar", "op"));
        FUNCTION_WORDS.put("sw", Set.of("na", "ya", "wa", "kwa", "ni", "za", "katika", "hii", "asante", "habari",
                "tafadhali", "akaunti", "kadi", "lakini"));
    }

    private LanguageDetector() {
    }

    public static String detect(String text) {
        if (text == null || text.isBlank()) return null;

        // 1. Alphabet non latin : décisif.
        Map<String, Integer> scripts = new LinkedHashMap<>();
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            String lang = switch (script) {
                case ARABIC -> "ar";
                case CYRILLIC -> "ru";
                case GREEK -> "el";
                case HEBREW -> "he";
                case THAI -> "th";
                case DEVANAGARI -> "hi";
                case ETHIOPIC -> "am";
                case HANGUL -> "ko";
                case HIRAGANA, KATAKANA -> "ja";
                case HAN -> "zh-CN";
                default -> null;
            };
            if (lang != null) scripts.merge(lang, 1, Integer::sum);
        }
        if (letters == 0) return null;
        if (!scripts.isEmpty()) {
            // Le japonais mélange kanji (HAN) et kana : la présence de kana l'emporte.
            if (scripts.containsKey("ja")) return "ja";
            String best = scripts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            if (scripts.get(best) * 2 >= letters) return best;
        }

        // 2. Alphabet latin : mots-outils.
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+", " ").trim();
        if (normalized.isEmpty()) return null;
        String[] words = normalized.split(" ");
        String bestLang = null;
        int bestScore = 0;
        int secondScore = 0;
        for (Map.Entry<String, Set<String>> entry : FUNCTION_WORDS.entrySet()) {
            int score = 0;
            for (String w : words) if (entry.getValue().contains(w)) score++;
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                bestLang = entry.getKey();
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        // Indices spécifiques en cas d'égalité ou de texte très court.
        String lower = text.toLowerCase(Locale.ROOT);
        if (bestScore == 0 || bestScore == secondScore) {
            if (lower.matches("(?s).*([çœéèêàù]|\\b(l|d|j|qu|n|c|s)['’]).*")) return "fr";
            if (lower.matches("(?s).*[ñ¿¡].*")) return "es";
            if (lower.matches("(?s).*[ãõ].*")) return "pt";
            if (lower.matches("(?s).*[äöüß].*")) return "de";
            return bestScore == 0 ? null : bestLang;
        }
        return bestLang;
    }
}

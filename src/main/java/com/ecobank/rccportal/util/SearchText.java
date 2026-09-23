package com.ecobank.rccportal.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Moteur de pertinence partagé par toutes les barres de recherche du portail (recherche
 * globale / RAF, Base de connaissances, Procédures) — remplace les anciens
 * {@code LIKE %mot%} et {@code String.contains()} qui produisaient des résultats
 * incohérents :
 * <ul>
 *   <li>sensibles aux accents (« procedure » ne trouvait pas « procédure » avec une
 *       collation SQL Server {@code _AS}) ;</li>
 *   <li>une requête de plusieurs mots devait apparaître telle quelle (« carte bloquée »
 *       ne trouvait pas « blocage de la carte ») ;</li>
 *   <li>un mot court matchait n'importe où (« art » trouvait « carte ») ;</li>
 *   <li>aucune tolérance aux fautes de frappe (« virment » → rien).</li>
 * </ul>
 * Ici : texte normalisé (sans accent, minuscules), mots vides retirés, racinisation
 * légère du français (pluriels, féminins), correspondance exacte &gt; préfixe &gt; approchée
 * (distance de Levenshtein), et pondération par champ (titre &gt; mots-clés &gt; contenu).
 */
public final class SearchText {

    /** Mots vides FR/EN déjà désaccentués. */
    private static final Set<String> STOPWORDS = Set.of(
            "le", "la", "les", "un", "une", "des", "de", "du", "et", "ou", "a", "au", "aux",
            "ce", "ces", "cette", "cet", "que", "qui", "quoi", "pour", "avec", "sur", "sous", "dans",
            "en", "est", "sont", "vous", "votre", "vos", "nous", "notre", "nos", "je", "tu", "il",
            "elle", "on", "ils", "elles", "se", "sa", "son", "ses", "leur", "leurs", "pas", "ne",
            "plus", "bien", "etre", "avoir", "faire", "par", "comment", "quel", "quelle", "quels",
            "quelles", "mon", "ma", "mes", "ton", "ta", "tes", "y", "l", "d", "j", "c", "s", "n", "m",
            "qu", "si", "mais", "donc", "car", "ni", "the", "of", "and", "or", "to", "in", "for",
            "with", "is", "are", "an", "how", "what", "my", "your");

    private SearchText() {
    }

    /** Minuscules, sans accent, ponctuation remplacée par des espaces. */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        String withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return withoutAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim();
    }

    /** Retire le HTML (contenu riche des articles) avant indexation. */
    public static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * Racinisation volontairement légère (pas un vrai stemmer) : suffit à rapprocher
     * « cartes/carte », « bloquée/bloque/bloqué », « virements/virement » sans produire de
     * faux positifs agressifs.
     */
    public static String stem(String word) {
        String w = word;
        if (w.length() > 4 && (w.endsWith("s") || w.endsWith("x"))) w = w.substring(0, w.length() - 1);
        if (w.length() > 4 && w.endsWith("e")) w = w.substring(0, w.length() - 1);
        if (w.length() > 5 && w.endsWith("e")) w = w.substring(0, w.length() - 1); // « bloquee » → « bloqu »
        return w;
    }

    /** Mots significatifs d'une requête (normalisés + racinisés), dans l'ordre, sans doublon. */
    public static List<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String word : normalize(query).split(" ")) {
            if (word.isEmpty() || STOPWORDS.contains(word)) continue;
            // Un mot d'une lettre n'a pas de sens ; 2 lettres seulement pour les sigles/nombres (« CB », « 24 »).
            if (word.length() < 2) continue;
            terms.add(stem(word));
        }
        return new ArrayList<>(terms);
    }

    /** Index d'un champ : l'ensemble de ses mots racinisés. */
    public static Set<String> index(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String word : normalize(text).split(" ")) {
            if (!word.isEmpty()) tokens.add(stem(word));
        }
        return tokens;
    }

    /** Un champ pondéré à scorer (titre, mots-clés, contenu...). */
    public record Field(String text, double weight) {
        public static Field of(String text, double weight) {
            return new Field(text, weight);
        }
    }

    /** Résultat : score global + proportion des mots de la requête retrouvés (0..1). */
    public record Match(double score, double coverage) {
        public boolean isRelevant(int termCount) {
            if (score <= 0) return false;
            // Requête de plusieurs mots : au moins la moitié doit être retrouvée — évite qu'un
            // seul mot générique (« client », « compte ») fasse remonter tout le catalogue.
            return termCount <= 1 || coverage >= 0.5;
        }
    }

    /**
     * Score d'un document pour une requête. Pour chaque mot de la requête, on retient la
     * meilleure correspondance parmi les champs : exacte (1.0), préfixe (0.7) ou approchée
     * (0.45). Bonus si la requête entière apparaît telle quelle dans un champ.
     */
    public static Match score(String rawQuery, List<String> terms, Field... fields) {
        if (terms.isEmpty()) return new Match(0, 0);
        List<Set<String>> indexes = new ArrayList<>(fields.length);
        for (Field f : fields) indexes.add(index(f.text()));

        double total = 0;
        int matched = 0;
        for (String term : terms) {
            double best = 0;
            for (int i = 0; i < fields.length; i++) {
                double quality = bestTokenMatch(term, indexes.get(i));
                best = Math.max(best, quality * fields[i].weight());
            }
            if (best > 0) matched++;
            total += best;
        }

        String phrase = normalize(rawQuery);
        if (terms.size() > 1 && !phrase.isEmpty()) {
            for (Field f : fields) {
                if (normalize(f.text()).contains(phrase)) {
                    total += f.weight();
                    break;
                }
            }
        }
        return new Match(total, (double) matched / terms.size());
    }

    private static double bestTokenMatch(String term, Set<String> tokens) {
        if (tokens.contains(term)) return 1.0;
        double best = 0;
        for (String token : tokens) {
            if (term.length() >= 3 && token.startsWith(term)) {
                best = Math.max(best, 0.7);
            } else if (term.length() >= 5 && token.length() >= 4 && Math.abs(token.length() - term.length()) <= 2) {
                int maxDistance = term.length() >= 8 ? 2 : 1;
                if (levenshtein(term, token, maxDistance) <= maxDistance) best = Math.max(best, 0.45);
            }
            if (best >= 0.7) break;
        }
        return best;
    }

    /** Distance de Levenshtein bornée (arrêt anticipé au-delà de {@code max}). */
    static int levenshtein(String a, String b, int max) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > max) return max + 1;
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    /**
     * Extrait centré sur le premier mot de la requête trouvé dans le texte — plus utile
     * qu'un simple début de texte quand le passage pertinent est au milieu de l'article.
     */
    public static String snippetAround(String text, List<String> terms, int maxLength) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLength) return text;
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        int pos = -1;
        // La normalisation NFD+suppression des diacritiques conserve la longueur pour le
        // latin courant, donc la position trouvée est réutilisable sur le texte d'origine.
        if (normalized.length() == text.length()) {
            for (String term : terms) {
                int idx = normalized.indexOf(term);
                if (idx >= 0 && (pos < 0 || idx < pos)) pos = idx;
            }
        }
        if (pos < 0) return text.substring(0, maxLength) + "…";
        int start = Math.max(0, pos - maxLength / 3);
        int end = Math.min(text.length(), start + maxLength);
        start = Math.max(0, end - maxLength);
        return (start > 0 ? "…" : "") + text.substring(start, end).trim() + (end < text.length() ? "…" : "");
    }
}

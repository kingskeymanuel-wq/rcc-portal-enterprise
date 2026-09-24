package com.ecobank.rccportal.util;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;

/**
 * Rapprochement de noms de personnes pour éviter les comptes en double : ordre des mots, casse,
 * accents, ponctuation et suffixes d'import (« (2°) », « 2 ») ignorés ; un prénom en plus ou en
 * moins et une petite faute de frappe tolérés (« MOKE CARMELA » = « MOKE Marie Carmella »).
 * Utilisé par la fusion des doublons (UserDuplicateService) et par les imports (planning, KPI,
 * présence, roster) avant toute création de compte.
 */
public final class PersonNames {

    private PersonNames() {}

    /** Mots significatifs du nom (≥ 2 lettres), sans accents, en minuscules. */
    public static List<String> tokens(String name) {
        if (name == null) return List.of();
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("\\(.*?\\)", " ")      // « (2°) », « (doublon) »
                .replaceAll("[^a-z\\s'-]", " ")    // chiffres, ponctuation
                .replaceAll("['-]", " ");
        List<String> out = new ArrayList<>();
        for (String w : s.trim().split("\\s+")) if (w.length() > 1) out.add(w);
        return out;
    }

    /** Clé exacte : mots triés (« Awa KONÉ » = « kone awa »). */
    public static String key(String name) {
        List<String> t = new ArrayList<>(tokens(name));
        Collections.sort(t);
        return String.join(" ", t);
    }

    /**
     * Même personne probable : tous les mots du nom le plus court (au moins 2) se retrouvent dans
     * l'autre, à une lettre près pour les mots de 5 lettres ou plus.
     */
    public static boolean similar(String a, String b) {
        List<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        List<String> small = ta.size() <= tb.size() ? ta : tb;
        List<String> large = new ArrayList<>(ta.size() <= tb.size() ? tb : ta);
        if (small.size() < 2) return false;
        for (String w : small) {
            int hit = -1;
            for (int i = 0; i < large.size(); i++) {
                if (wordMatch(w, large.get(i))) { hit = i; break; }
            }
            if (hit < 0) return false;
            large.remove(hit);
        }
        return true;
    }

    static boolean wordMatch(String x, String y) {
        if (x.equals(y)) return true;
        if (x.length() < 5 || y.length() < 5) return false;
        return levenshtein(x, y) <= 1;
    }

    static int levenshtein(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) return 2;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** L'unique candidat qui correspond au nom (null si aucun ou plusieurs : on ne devine pas). */
    public static <T> T findUnique(String name, Collection<T> candidates, Function<T, String> nameOf) {
        T found = null;
        Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (T c : candidates) {
            if (c == null || !seen.add(c)) continue;
            if (similar(name, nameOf.apply(c))) {
                if (found != null) return null;
                found = c;
            }
        }
        return found;
    }
}

package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.language.French;
import org.languagetool.rules.RuleMatch;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Correcteur orthographe/grammaire — LanguageTool, moteur 100% à base de règles et de
 * dictionnaires, exécuté en mémoire dans la JVM. AUCUNE dépendance à un LLM/IA, AUCUN appel
 * réseau : contrainte explicite du projet (voir échange avec td du 17/09/2026), contrairement
 * aux outils comme Reverso/Grammarly qui s'appuient sur un service IA externe.
 *
 * <p>Un {@link JLanguageTool} par langue est coûteux à construire (chargement des règles et
 * dictionnaires) — on le construit une seule fois par langue et on le réutilise (voir
 * {@link #instanceFor(String)}). Les instances sont thread-safe pour {@code check()} d'après la
 * documentation LanguageTool.</p>
 *
 * <p>Seuls le français et l'anglais sont embarqués (voir pom.xml — language-fr / language-en)
 * pour éviter le poids de language-all (~150 Mo, toutes langues). Étendre à d'autres langues
 * UEMOA nécessiterait d'ajouter le module Maven correspondant s'il existe.</p>
 */
@Service
public class LocalSpellCheckClient {

    private static final ConcurrentHashMap<String, JLanguageTool> INSTANCES = new ConcurrentHashMap<>();

    public record Issue(
            String message,
            String shortMessage,
            int offset,
            int length,
            List<String> suggestions,
            String ruleId,
            String category) {
    }

    public record SpellCheckResult(String language, List<Issue> issues) {
    }

    public boolean isSupported(String lang) {
        return normalizeLang(lang) != null;
    }

    /**
     * Vérifie un texte et renvoie la liste des erreurs détectées (orthographe, grammaire,
     * ponctuation, typographie) avec leurs suggestions de correction — jamais de réécriture
     * automatique du texte : c'est à l'agent de choisir la suggestion à appliquer, exactement
     * comme le bouton "Vérifier" de Reverso (pas "AI Rephraser", volontairement absent ici).
     */
    public SpellCheckResult check(String text, String lang) {
        if (text == null || text.isBlank()) {
            return new SpellCheckResult(lang, List.of());
        }
        String normalized = normalizeLang(lang);
        if (normalized == null) {
            throw ApiException.badRequest(
                    "Langue non prise en charge par le correcteur local : " + lang + " (fr/en uniquement).");
        }

        try {
            JLanguageTool tool = instanceFor(normalized);
            List<RuleMatch> matches = tool.check(text);
            List<Issue> issues = matches.stream()
                    .map(m -> new Issue(
                            m.getMessage(),
                            m.getShortMessage(),
                            m.getFromPos(),
                            m.getToPos() - m.getFromPos(),
                            m.getSuggestedReplacements(),
                            m.getRule().getId(),
                            m.getRule().getCategory() != null ? m.getRule().getCategory().getName() : null
                    ))
                    .collect(Collectors.toList());
            return new SpellCheckResult(normalized, issues);
        } catch (java.io.IOException e) {
            // JLanguageTool#check déclare IOException (règles distantes en théorie) — jamais
            // levée ici puisqu'aucune règle réseau n'est activée, mais on la traduit proprement.
            throw ApiException.serviceUnavailable("Correcteur local : erreur interne (" + e.getMessage() + ").");
        }
    }

    private JLanguageTool instanceFor(String normalized) {
        return INSTANCES.computeIfAbsent(normalized, key -> switch (key) {
            case "fr" -> new JLanguageTool(new French());
            case "en" -> new JLanguageTool(new AmericanEnglish());
            default -> throw new IllegalStateException("Langue non gérée : " + key);
        });
    }

    private String normalizeLang(String lang) {
        if (lang == null) return null;
        String l = lang.trim().toLowerCase(Locale.ROOT);
        if (l.startsWith("fr")) return "fr";
        if (l.startsWith("en")) return "en";
        return null;
    }
}

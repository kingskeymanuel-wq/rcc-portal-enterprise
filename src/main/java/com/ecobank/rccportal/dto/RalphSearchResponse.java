package com.ecobank.rccportal.dto;

import java.util.List;

/**
 * @param source d'où vient la réponse — INTERNAL (Base de connaissances / procédures
 *               Ecobank), WEB (repli recherche web, aucun résultat interne pertinent) ou
 *               MIXED (contexte interne + web combinés pour la synthèse IA). Toujours
 *               renseigné pour que le frontend puisse afficher la provenance à l'agent.
 * @param webResults résultats web utilisés en repli, le cas échéant (liste vide sinon) —
 *                    permet d'afficher les liens sources distinctement des procédures internes.
 * @param confidencePercent niveau de confiance indicatif (0-100), calculé à partir de la
 *                           qualité des correspondances internes et de l'origine de la
 *                           réponse — jamais une mesure statistique rigoureuse, un repère
 *                           pour l'agent afin de savoir s'il doit vérifier davantage.
 * @param sourcesConsulted libellés des sources effectivement consultées pour cette réponse
 *                          (ex. "Procédures internes", "Base de connaissances", "Copilot
 *                          Studio", "Recherche web"), dans l'ordre de priorité Ecobank.
 */
public record RalphSearchResponse(
        String explanation,
        List<RalphResultItem> results,
        String source,
        List<WebSearchResultItem> webResults,
        int confidencePercent,
        List<String> sourcesConsulted,
        // RAF v2 (agents locaux) — champs ajoutés en fin, null/vides pour la barre de recherche.
        String intent,
        List<RafAgentTrace> agentsConsulted,
        List<RafSuggestion> suggestions,
        RafAction action,
        boolean clarification,
        List<String> reasoning
) {
    /** Compatibilité — réponse sans métadonnées RAF (barre de recherche classique, anciens appelants). */
    public RalphSearchResponse(String explanation, List<RalphResultItem> results, String source,
                               List<WebSearchResultItem> webResults, int confidencePercent, List<String> sourcesConsulted) {
        this(explanation, results, source, webResults, confidencePercent, sourcesConsulted,
                null, List.of(), List.of(), null, false, List.of());
    }

    /** Compatibilité avec l'ancien constructeur à 2 arguments (recherche mots-clés locale). */
    public RalphSearchResponse(String explanation, List<RalphResultItem> results) {
        this(explanation, results, "INTERNAL", List.of(), results.isEmpty() ? 0 : 60, List.of("Base de connaissances", "Procédures internes"));
    }
}

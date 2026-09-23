package com.ecobank.rccportal.dto;

import java.util.List;

/**
 * narrative : synthèse générée par l'IA, uniquement à partir des chiffres réels ci-dessous —
 * jamais de données inventées. serviceBreakdown : les vrais agrégats utilisés comme contexte,
 * affichés en transparence pour que l'IT puisse vérifier ce sur quoi l'IA s'est basée.
 */
public record DataAnalysisResponse(
        String narrative,
        String periodMonth,
        int totalAgents,
        Double avgPresenceRate,
        Double avgQualityScore,
        Double avgPerformanceGlobale,
        List<TeamBreakdown> serviceBreakdown,
        boolean aiAvailable
) {
    /**
     * serviceName conserve son nom historique mais contient désormais le libellé de l'ÉQUIPE
     * (activity), pas du service — un seul service "RCC" existe désormais, l'axe utile pour le
     * management est l'équipe. avgInteractions : proxy réel du nombre d'appels/contacts traités
     * (rubrique "Interactions" des imports KPI). totalAbsenceDays / totalPauseOverruns : comptés
     * directement depuis les vrais événements de pointage/congés, jamais estimés par l'IA.
     * pctBelowTarget : % d'agents de l'équipe dont Interactions < Target ce mois, quand les deux
     * sont renseignés.
     */
    public record TeamBreakdown(
            String serviceName,
            String teamCode,
            int agentCount,
            Double avgPresenceRate,
            Double avgQualityScore,
            Double avgPerformanceGlobale,
            Double avgInteractions,
            int totalAbsenceDays,
            int totalPauseOverruns,
            Double pctBelowTarget
    ) {
    }
}

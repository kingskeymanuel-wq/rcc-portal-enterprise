package com.ecobank.rccportal.dto;

import java.util.Map;

/**
 * periodMonth au format "YYYY-MM". kpiMetrics : clé = code métrique
 * (CAS_CREES, MAUVAIS_ENREGISTREMENT, ACTIVITE_PRINCIPALE, AUTRES_ACTIVITES,
 * TAUX_ATTEINTE_OBJECTIF, CAS_CREES_PCT, TAUX_BONNE_CREATION_CAS), valeur = la
 * dernière saisie pour ce mois. performanceGlobale : null si les 4 métriques
 * nécessaires au calcul (voir PerformanceService) ne sont pas toutes saisies —
 * jamais un score approximatif silencieux.
 */
public record PerformanceResponse(
        String username, String userFullName, String periodMonth,
        int evaluationCount, Double avgQualityScore,
        Map<String, Double> kpiMetrics, double presenceRate, Double performanceGlobale,
        String affiliateBranch, String serviceName, String activity, Long userId) {
}
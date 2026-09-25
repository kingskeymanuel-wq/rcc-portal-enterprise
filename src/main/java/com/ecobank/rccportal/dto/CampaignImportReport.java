package com.ecobank.rccportal.dto;

import java.util.List;
import java.util.Map;

/**
 * Bilan d'un import de contacts de campagne : ce qui a été chargé, ce qui a été écarté et
 * pourquoi, la répartition par statut et l'affectation aux agents (reconnus par identifiant
 * ou par nom complet). Un réimport synchronise la campagne : les contacts déjà présents sont
 * mis à jour (updated) au lieu d'être recréés ; ceux dont les données du portail sont plus
 * récentes que le fichier restent inchangés (unchanged).
 * matchedQuestions / totalQuestions / unmatchedQuestions : alignement sur le modèle de questions
 * de la campagne.
 */
public record CampaignImportReport(
        int imported,
        int skippedDuplicates,
        int skippedNotToRecall,
        int skippedWithoutName,
        int assigned,
        Map<String, Integer> statusCounts,
        Map<String, Integer> matchedAgents,
        Map<String, Integer> unmatchedAgents,
        int updated,
        int unchanged,
        int matchedQuestions,
        int totalQuestions,
        List<String> unmatchedQuestions
) {
}

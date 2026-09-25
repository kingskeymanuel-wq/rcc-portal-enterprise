package com.ecobank.rccportal.dto;

import java.util.Map;

/**
 * Bilan d'un import de contacts de campagne : ce qui a été chargé, ce qui a été écarté et
 * pourquoi, la répartition par statut et l'affectation aux agents (reconnus par identifiant
 * ou par nom complet).
 */
public record CampaignImportReport(
        int imported,
        int skippedDuplicates,
        int skippedNotToRecall,
        int skippedWithoutName,
        int assigned,
        Map<String, Integer> statusCounts,
        Map<String, Integer> matchedAgents,
        Map<String, Integer> unmatchedAgents
) {
}

package com.ecobank.rccportal.dto;

import java.util.List;

/**
 * Réponse de POST /api/campaigns/{id}/import/preview — lue une seule fois côté serveur pour
 * proposer un mapping par défaut, que l'utilisateur ajuste ensuite dans la modale avant de
 * confirmer l'import (voir CampaignImportMappingDto). headers/sampleRow permettent d'afficher
 * à l'écran le contenu réel de chaque colonne du fichier fourni.
 *
 * Synchronisation avec le modèle de la campagne : matchedQuestions / unmatchedQuestions (questions
 * du modèle retrouvées ou non dans le fichier), unmatchedColumns (colonnes renseignées du fichier
 * qui ne correspondent à rien — conservées en informations complémentaires) et confident : le
 * fichier est reconnu sans ambiguïté, l'import peut partir immédiatement sans vérification.
 */
public record CampaignImportPreviewResponse(
        List<String> headers,
        List<String> sampleRow,
        CampaignImportMappingDto suggestedMapping,
        List<CampaignFieldDto> campaignFields,
        int matchedQuestions,
        List<String> unmatchedQuestions,
        List<String> unmatchedColumns,
        boolean confident
) {
    public CampaignImportPreviewResponse(List<String> headers, List<String> sampleRow, CampaignImportMappingDto suggestedMapping,
                                         List<CampaignFieldDto> campaignFields) {
        this(headers, sampleRow, suggestedMapping, campaignFields, 0, List.of(), List.of(), false);
    }
}

package com.ecobank.rccportal.dto;

import java.util.List;

/**
 * Réponse de POST /api/campaigns/{id}/import/preview — lue une seule fois côté serveur pour
 * proposer un mapping par défaut, que l'utilisateur ajuste ensuite dans la modale avant de
 * confirmer l'import (voir CampaignImportMappingDto). headers/sampleRow permettent d'afficher
 * à l'écran le contenu réel de chaque colonne du fichier fourni, pour que l'utilisateur puisse
 * l'identifier même si l'en-tête est ambigu ou absent.
 */
public record CampaignImportPreviewResponse(
        List<String> headers,
        List<String> sampleRow,
        CampaignImportMappingDto suggestedMapping,
        List<CampaignFieldDto> campaignFields
) {
}

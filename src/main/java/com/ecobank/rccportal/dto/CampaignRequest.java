package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.util.List;

public record CampaignRequest(
        String name,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        /** "DIGITAL" | "TELEVENTE" | null (visible par toute l'équipe Outbound). */
        String targetService,
        String iconClass,
        String colorFrom,
        String colorTo,
        /** Photo de couverture de la tuile (grille "Campagne") — voir Campaign.coverImageUrl. */
        String coverImageUrl,
        /** Modèle de questions de la campagne — null/vide = aucune question additionnelle. */
        List<CampaignFieldDto> fields,
        /** Formulaire v2 (prioritaire sur fields) — voir CampaignFormEngine. */
        com.fasterxml.jackson.databind.JsonNode form
) {
    /** Compatibilité : campagne créée avec l'ancienne liste de questions. */
    public CampaignRequest(String name, String description, LocalDate startDate, LocalDate endDate, String targetService, String iconClass,
                           String colorFrom, String colorTo, String coverImageUrl, List<CampaignFieldDto> fields) {
        this(name, description, startDate, endDate, targetService, iconClass, colorFrom, colorTo, coverImageUrl, fields, null);
    }
}

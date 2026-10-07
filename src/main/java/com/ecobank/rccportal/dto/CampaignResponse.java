package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record CampaignResponse(
        Integer campaignId,
        String name,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        String targetService,
        String iconClass,
        String colorFrom,
        String colorTo,
        String coverImageUrl,
        List<CampaignFieldDto> fields,
        LocalDateTime createdAt,
        int totalContacts,
        int callsMade,
        int contacted,
        int appointmentsTaken,
        int unassigned,
        /** Formulaire v2 de la campagne (toujours présent : l'ancienne liste de questions y est convertie). */
        com.fasterxml.jackson.databind.JsonNode form
) {
}

package com.ecobank.rccportal.dto;

public record GameDefinitionResponse(
        Integer gameId,
        String gameKey,
        String mechanic,
        String title,
        String description,
        String icon,
        String colorFrom,
        String colorTo,
        String configJson,
        Integer sortOrder,
        Boolean active,
        Integer bestScore,
        String targetTeam
) {}

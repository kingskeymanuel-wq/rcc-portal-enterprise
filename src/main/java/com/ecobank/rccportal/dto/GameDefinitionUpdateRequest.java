package com.ecobank.rccportal.dto;

/** targetTeam : null = pas de changement, "" = rendre générique (toutes équipes), sinon un code TeamClassifier.Team. */
public record GameDefinitionUpdateRequest(
        String title,
        String description,
        String configJson,
        Boolean active,
        String targetTeam
) {}

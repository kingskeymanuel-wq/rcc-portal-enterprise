package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

/** Vue allégée pour les listes (sans le détail des étapes) — voir ProcedureResponse pour le détail. */
public record ProcedureSummaryResponse(
        Integer id, String zoneCode, String serviceCode, String serviceName, String countryCode, String title, int stepCount,
        boolean hasWorkflow, boolean isFavorite,
        String createdByUserId, LocalDateTime createdAt, LocalDateTime updatedAt) {
}

package com.ecobank.rccportal.dto;

/**
 * Décompte d'utilisation d'un service — pour que l'admin voie l'impact réel avant de
 * consolider vers un service unique (RCC). Jamais de suppression sans que ces chiffres
 * aient été vus.
 */
public record ServiceUsageResponse(
        Long serviceId,
        String code,
        String name,
        long articleCount,
        long procedureCount,
        long courseCount,
        long workflowRequestCount,
        long userAssignmentCount
) {
}

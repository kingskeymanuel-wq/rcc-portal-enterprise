package com.ecobank.rccportal.dto;

import java.util.List;

/** Résultat d'une consolidation "tout vers RCC" — ce qui a été réassigné puis supprimé. */
public record ServiceConsolidationResult(
        String survivingServiceCode,
        List<String> deletedServiceCodes,
        long articlesReassigned,
        long proceduresReassigned,
        long coursesReassigned,
        long workflowRequestsReassigned,
        long userAssignmentsReassigned
) {
}

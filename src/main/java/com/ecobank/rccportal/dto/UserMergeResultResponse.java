package com.ecobank.rccportal.dto;

import java.util.List;

public record UserMergeResultResponse(
        String username,
        Long keptUserId,
        List<Long> mergedUserIds,
        int rowsReassigned,
        int rowsDeduplicatedAway
) {
}

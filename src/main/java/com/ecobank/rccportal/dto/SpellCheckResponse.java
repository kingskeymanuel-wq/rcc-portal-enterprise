package com.ecobank.rccportal.dto;

import java.util.List;

public record SpellCheckResponse(
        String language,
        List<IssueDto> issues
) {
    public record IssueDto(
            String message,
            String shortMessage,
            int offset,
            int length,
            List<String> suggestions,
            String ruleId,
            String category
    ) {
    }
}

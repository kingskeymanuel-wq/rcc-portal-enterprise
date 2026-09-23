package com.ecobank.rccportal.dto;

import java.util.List;

public record SpellCheckResponse(
        String language,
        List<IssueDto> issues,
        String engine
) {
    public record IssueDto(
            String message,
            String shortMessage,
            int offset,
            int length,
            List<String> suggestions,
            String ruleId,
            String category,
            /** misspelling / grammar / typographical / style / other — couleur du surlignage. */
            String type
    ) {
    }
}

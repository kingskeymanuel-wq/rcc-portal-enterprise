package com.ecobank.rccportal.dto;

import com.ecobank.rccportal.model.SlaRule;

public record SlaRuleResponse(
        Integer id,
        String motif,
        String category,
        String level,
        Integer slaHours,
        String slaLabel,
        String destinationService,
        String priority,
        Boolean autoEscalation,
        String notes,
        Boolean isActive,
        Integer sortOrder,
        Integer poleId
) {
    public static SlaRuleResponse from(SlaRule r) {
        return new SlaRuleResponse(
                r.getSlaRuleId(),
                r.getMotif(),
                r.getCategory(),
                r.getLevel(),
                r.getSlaHours(),
                r.getSlaLabel(),
                r.getDestinationService(),
                r.getPriority(),
                r.getAutoEscalation(),
                r.getNotes(),
                r.getIsActive(),
                r.getSortOrder(),
                r.getPole() != null ? r.getPole().getPoleId() : null
        );
    }
}

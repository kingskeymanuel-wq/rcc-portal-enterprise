package com.ecobank.rccportal.dto;

import java.util.List;

public record MailTemplatesOverviewResponse(
        List<CategoryDto> categories,
        List<RecipientGroupDto> recipientGroups,
        List<MailTemplateResponse> templates) {

    public record CategoryDto(Integer id, String code, String label, String accentColor, Integer sortOrder, String team) {
    }

    public record RecipientGroupDto(Integer id, String label, String email) {
    }
}

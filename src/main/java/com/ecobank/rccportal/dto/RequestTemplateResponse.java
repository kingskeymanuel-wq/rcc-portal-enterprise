package com.ecobank.rccportal.dto;

public record RequestTemplateResponse(
        Integer templateId,
        String name,
        String type,
        String defaultTitle,
        String defaultDetails,
        String defaultAssignedTeam,
        Boolean active
) {}

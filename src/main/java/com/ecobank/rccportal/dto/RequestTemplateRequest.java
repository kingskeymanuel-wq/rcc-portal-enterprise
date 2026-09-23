package com.ecobank.rccportal.dto;

public record RequestTemplateRequest(
        String name,
        String type,
        String defaultTitle,
        String defaultDetails,
        String defaultAssignedTeam,
        Boolean active
) {}

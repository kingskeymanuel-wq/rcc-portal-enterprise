package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record RccPoleRequest(
        @NotBlank String name,
        String managerUsername,
        String contactPhone,
        String contactEmail,
        String teamContactLabel,
        String whoWeAre,
        String whatWeDo,
        Boolean isActive,
        Integer sortOrder
) {
}

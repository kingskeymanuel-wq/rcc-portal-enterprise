package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record BankBranchRequest(
        @NotBlank String countryCode,
        @NotBlank String city,
        @NotBlank String name,
        String address,
        Double latitude,
        Double longitude,
        String phone,
        String email,
        String openingHours,
        String managerName,
        String branchType,
        Boolean active
) {
}

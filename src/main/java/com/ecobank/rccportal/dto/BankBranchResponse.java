package com.ecobank.rccportal.dto;

import com.ecobank.rccportal.model.BankBranch;

public record BankBranchResponse(
        Long id,
        String countryCode,
        String city,
        String name,
        String address,
        Double latitude,
        Double longitude,
        String phone,
        String email,
        String openingHours,
        String managerName,
        String branchType,
        boolean active
) {
    public static BankBranchResponse from(BankBranch b) {
        return new BankBranchResponse(
                b.getBranchId(),
                b.getCountryCode(),
                b.getCity(),
                b.getName(),
                b.getAddress(),
                b.getLatitude(),
                b.getLongitude(),
                b.getPhone(),
                b.getEmail(),
                b.getOpeningHours(),
                b.getManagerName(),
                b.getBranchType(),
                b.isActive()
        );
    }
}

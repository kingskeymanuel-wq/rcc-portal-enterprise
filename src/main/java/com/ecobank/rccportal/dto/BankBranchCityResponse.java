package com.ecobank.rccportal.dto;

public record BankBranchCityResponse(
        String city,
        long branchCount,
        Double centerLatitude,
        Double centerLongitude
) {
}

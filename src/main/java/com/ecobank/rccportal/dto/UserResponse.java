package com.ecobank.rccportal.dto;

import java.time.LocalDate;

public record UserResponse(
        Integer id,
        String username,
        String fullName,
        String email,
        String role,
        String service,
        String affiliateBranch,
        Boolean active,
        String gender,
        String contractType,
        String contractStatus,
        LocalDate contractStartDate,
        LocalDate contractEndDate,
        String activity,
        String photoUrl,
        String residencePlace,
        String ledTeam
) {
}

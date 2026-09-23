package com.ecobank.rccportal.dto;

import java.time.LocalDate;

public record UserProfileResponse(
        String matricule, String name, String photoUrl, String chatBackgroundUrl, LocalDate birthdate, String phone, String bio) {
}

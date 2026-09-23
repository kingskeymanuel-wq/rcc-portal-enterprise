package com.ecobank.rccportal.dto;

import java.time.LocalDate;

/** Mise à jour partielle : chaque champ non nul remplace la valeur existante. */
public record UserProfileRequest(String photoUrl, String chatBackgroundUrl, LocalDate birthdate, String phone, String bio) {
}

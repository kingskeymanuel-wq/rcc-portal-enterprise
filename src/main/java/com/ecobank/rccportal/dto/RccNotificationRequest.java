package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

/** countryCode/serviceCode/activity optionnels — tous absents = diffusion globale (comportement historique). */
public record RccNotificationRequest(
        @NotBlank(message = "content is required.") String content,
        String countryCode,
        String serviceCode,
        String activity) {
}

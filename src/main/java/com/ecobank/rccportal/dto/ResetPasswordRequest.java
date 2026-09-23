package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
        @NotBlank(message = "challengeId is required.") String challengeId,
        @NotBlank(message = "code is required.") String code,
        @NotBlank(message = "password is required.") String password) {
}

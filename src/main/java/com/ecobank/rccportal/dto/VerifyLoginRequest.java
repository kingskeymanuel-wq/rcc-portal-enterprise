package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyLoginRequest(
        @NotBlank String challengeId,
        @NotBlank String code
) {}

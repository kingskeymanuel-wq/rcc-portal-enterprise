package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record TeamRequest(
        @NotBlank(message = "code is required.") String code,
        @NotBlank(message = "label is required.") String label,
        String iconGlyph,
        String accentColor) {
}

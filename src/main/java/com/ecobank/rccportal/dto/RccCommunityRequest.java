package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record RccCommunityRequest(
        @NotBlank(message = "communityKey is required.") String communityKey,
        @NotBlank(message = "label is required.") String label,
        Integer sortOrder) {
}

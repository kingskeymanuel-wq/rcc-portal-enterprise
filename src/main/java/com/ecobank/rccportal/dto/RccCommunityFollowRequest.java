package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record RccCommunityFollowRequest(
        @NotBlank(message = "communityKey is required.") String communityKey) {
}

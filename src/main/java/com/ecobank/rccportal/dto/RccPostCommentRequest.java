package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record RccPostCommentRequest(
        @NotBlank(message = "content is required.") String content) {
}

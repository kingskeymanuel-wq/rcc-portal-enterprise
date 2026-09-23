package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record AttachmentRequest(
        @NotBlank(message = "fileName is required.") String fileName,
        String mimeType,
        @NotBlank(message = "storageUrl is required.") String storageUrl) {
}

package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

/** teamCode et roleCode sont mutuellement exclusifs mais tous deux optionnels — voir TabPermissionService. */
public record TabPermissionRequest(
        String teamCode,
        String roleCode,
        @NotBlank(message = "tabCode is required.") String tabCode,
        boolean isAllowed) {
}

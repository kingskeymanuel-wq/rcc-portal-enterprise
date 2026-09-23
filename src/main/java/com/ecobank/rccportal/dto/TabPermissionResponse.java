package com.ecobank.rccportal.dto;

public record TabPermissionResponse(Integer id, String teamCode, String roleCode, String tabCode, boolean isAllowed) {
}

package com.ecobank.rccportal.dto;

public record RccServiceResponse(
        Long id, String name, String description, String path, String code,
        String icon, String color, String status, Boolean enabled,
        Integer displayOrder, Boolean openInNewTab, Boolean portalApp, String proxyCode
) {
}

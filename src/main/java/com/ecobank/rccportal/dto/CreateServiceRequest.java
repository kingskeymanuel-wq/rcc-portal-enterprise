package com.ecobank.rccportal.dto;

public record CreateServiceRequest(
        String name, String description, String path, String code, String icon,
        String color, Boolean enabled, Integer displayOrder, Boolean openInNewTab,
        Boolean portalApp, String proxyCode
) {
}
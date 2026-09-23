package com.ecobank.rccportal.dto;

public record DashboardActivityResponse(
        String time,
        String user,
        String action,
        String category
) {
}

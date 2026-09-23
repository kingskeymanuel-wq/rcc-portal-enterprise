package com.ecobank.rccportal.dto;

public record AdminDashboardResponse(
        long users,
        long roles,
        long services,
        long activeSessions,
        long auditLogs
) {
}

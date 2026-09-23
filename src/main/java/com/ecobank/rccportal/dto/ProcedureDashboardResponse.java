package com.ecobank.rccportal.dto;

public record ProcedureDashboardResponse(
        long procedures,
        long categories,
        long workflows,
        long tariffs,
        long responses,
        long documents,
        long favorites
) {
}
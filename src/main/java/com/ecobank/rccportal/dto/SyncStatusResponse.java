package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record SyncStatusResponse(
        LocalDateTime lastKpiImportAt,
        LocalDateTime lastScheduleImportAt,
        LocalDateTime lastAnyImportAt,
        long totalKpiEntries,
        long totalImportsToday,
        boolean dataStaleWarning
) {
}

package com.ecobank.rccportal.dto;

import java.util.List;

public record AttendanceImportResult(int rowsProcessed, int recordsCreated, List<String> unresolvedNames) {
}

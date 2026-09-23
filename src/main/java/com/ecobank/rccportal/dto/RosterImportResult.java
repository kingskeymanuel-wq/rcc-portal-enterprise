package com.ecobank.rccportal.dto;

import java.util.List;

public record RosterImportResult(
        int rowsProcessed,
        int usersUpdated,
        int usersCreated,
        List<String> unresolvedNames,
        List<String> unresolvedServices,
        List<RosterPreviewRow> preview,
        boolean previewTruncated
) {
    public record RosterPreviewRow(
            String name, String username, String gender,
            String contractType, String contractStatus,
            String activity, String service
    ) {
    }
}

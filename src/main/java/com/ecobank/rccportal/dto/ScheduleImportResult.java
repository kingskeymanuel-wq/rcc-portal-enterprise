package com.ecobank.rccportal.dto;

import java.util.List;

public record ScheduleImportResult(
        int rowsProcessed,
        int entriesCreated,
        int usersAutoCreated,
        List<String> unknownMatricules,
        String aiReview,
        List<ImportedSchedulePreview> preview,
        boolean previewTruncated,
        /** Chaque ligne du fichier NON capturée (date ou heure illisible), avec sa raison
         *  exacte — jamais d'approximation silencieuse. Vide = 100% du fichier capturé. */
        List<String> skippedRows
) {
}

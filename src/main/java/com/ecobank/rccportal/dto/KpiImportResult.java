package com.ecobank.rccportal.dto;

import java.util.List;

public record KpiImportResult(
        int rowsProcessed,
        int entriesCreated,
        int usersAutoCreated,
        int usersLinkedToTeam,
        List<String> unknownMatricules,
        String aiReview,
        List<ImportedKpiPreview> preview,
        boolean previewTruncated,
        String importBatchId,
        /** Total de cellules contenant une valeur numérique détectées dans le fichier — sert de
         *  référence de complétude : doit toujours être égal à entriesCreated + skippedValues.size(). */
        int numericCellsDetected,
        /** Chaque valeur numérique du fichier NON capturée, avec sa raison exacte — jamais
         *  d'approximation silencieuse. Vide = 100% du fichier capturé. */
        List<SkippedValue> skippedValues
) {
    public record SkippedValue(String rowLabel, String columnLabel, String rawValue, String reason) {
    }
}

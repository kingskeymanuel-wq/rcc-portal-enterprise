package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

/** Un import Excel passé — pour l'historique et la suppression après coup. */
public record ImportBatchResponse(String batchId, LocalDateTime importedAt, Long entryCount, String enteredByUsername) {
}

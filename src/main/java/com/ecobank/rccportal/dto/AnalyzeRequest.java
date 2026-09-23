package com.ecobank.rccportal.dto;

/** La transcription est envoyée par le client (pas relue en base — rien n'y est stocké). */
public record AnalyzeRequest(String transcript) {
}

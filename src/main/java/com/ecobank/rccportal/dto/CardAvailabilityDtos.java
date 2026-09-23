package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** Échanges de l'onglet « Disponibilité des cartes » (CardAvailabilityController). */
public final class CardAvailabilityDtos {

    private CardAvailabilityDtos() {}

    public record CardResponse(Long id, String countryCode, String name, String category, String details, Integer sortOrder) {}

    public record CellResponse(Long cardId, String city, boolean available, String note, String updatedBy, LocalDateTime updatedAt) {}

    /** Tableau complet d'une filiale : cartes (lignes) × villes (colonnes) + cases renseignées. */
    public record MatrixResponse(String countryCode, List<String> cities, List<CardResponse> cards, List<CellResponse> cells,
                                 LocalDateTime lastUpdatedAt, String lastUpdatedBy) {}

    public record CardRequest(
            @NotBlank @Size(max = 2) String countryCode,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 80) String category,
            @Size(max = 1000) String details,
            Integer sortOrder) {}

    public record CellRequest(
            @NotNull Long cardId,
            @NotBlank @Size(max = 100) String city,
            @NotNull Boolean available,
            @Size(max = 500) String note) {}
}

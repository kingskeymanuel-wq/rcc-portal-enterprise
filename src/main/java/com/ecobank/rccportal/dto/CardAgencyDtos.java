package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Point de disponibilité des cartes par agence (CardAgencyStatusService). */
public final class CardAgencyDtos {

    private CardAgencyDtos() {}

    public record AgencyRow(Long id, LocalDate reportDate, String agency, String agencyCode, String cardStatus,
                            String pinStatus, List<String> cardTypes, String note, String updatedBy, LocalDateTime updatedAt) {}

    /** Point du jour demandé (par défaut le plus récent) + dates disponibles pour l'historique. */
    public record AgencyReport(String countryCode, LocalDate reportDate, List<LocalDate> dates, List<AgencyRow> rows,
                               List<String> allCardTypes, boolean current) {}

    public record AgencyRowRequest(
            @NotBlank @Size(max = 2) String countryCode,
            @NotNull LocalDate reportDate,
            @NotBlank @Size(max = 170) String agency,
            @Size(max = 20) String agencyCode,
            @NotBlank String cardStatus,
            @NotBlank String pinStatus,
            List<String> cardTypes,
            @Size(max = 500) String note) {}

    /** Tableau collé tel quel (Excel, e-mail, WhatsApp) : DATE | AGENCE | CARTE | CODE | TYPE DE CARTE. */
    public record PasteRequest(@NotBlank @Size(max = 2) String countryCode, @NotBlank @Size(max = 50000) String text,
                               LocalDate defaultDate) {}

    public record PasteResult(int imported, LocalDate reportDate, List<String> warnings) {}
}

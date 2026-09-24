package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CardAgencyDtos.*;
import com.ecobank.rccportal.model.CardAgencyStatus;
import com.ecobank.rccportal.repository.CardAgencyStatusRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Point de disponibilité des cartes par agence : import du tableau collé tel que transmis par la filiale. */
class CardAgencyStatusServiceTest {

    private final List<CardAgencyStatus> saved = new ArrayList<>();
    private final CardAgencyStatusRepository repo = mock(CardAgencyStatusRepository.class);
    private final CardAgencyStatusService service = new CardAgencyStatusService(repo);

    CardAgencyStatusServiceTest() {
        when(repo.findByCountryCodeIgnoreCaseAndReportDateAndAgencyIgnoreCase(anyString(), any(), anyString())).thenReturn(Optional.empty());
        when(repo.save(any(CardAgencyStatus.class))).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
    }

    /** Exactement la forme reçue (une cellule par ligne, lignes vides, date seulement sur la 1re agence). */
    private static final String PASTED = """
            DATE
            \t
            AGENCE
            \t
            CARTE
            \t
            CODE
            \t
            TYPE DE CARTE


            24-09-2026
            \t
            NIANGON K27
            \t
            OK
            \t
            OK
            \t
            CLASSIC, PLATINIUM, XPRESS, MX, GOLD


            AGHIEN K10
            \t
            OK
            \t
            OK
            \t
            CLASSIC, PLATINIUM, MX, GOLD


            BOUAKE K02
            \t
            OK
            \t
            OK
            \t
            CLASSIC, PLATINIUM, MX, GOLD


            BEL AIR K45
            \t
            OK
            \t
            OK
            \t
            CLASSIC, PLATINIUM, MX, GOLD
            """;

    @Test
    void importsTheTableExactlyAsPasted() {
        PasteResult r = service.importPaste(new PasteRequest("CI", PASTED, null), "QA");
        assertEquals(4, r.imported(), r.warnings().toString());
        assertEquals(LocalDate.of(2026, 9, 24), r.reportDate());
        CardAgencyStatus niangon = saved.get(0);
        assertEquals("NIANGON", niangon.getAgency());
        assertEquals("K27", niangon.getAgencyCode());
        assertEquals("OK", niangon.getCardStatus());
        assertEquals("OK", niangon.getPinStatus());
        assertEquals("CLASSIC, PLATINIUM, XPRESS, MX, GOLD", niangon.getCardTypes());
        assertEquals("BEL AIR", saved.get(3).getAgency());
        assertEquals("K45", saved.get(3).getAgencyCode());
        assertTrue(saved.stream().allMatch(s -> s.getReportDate().equals(LocalDate.of(2026, 9, 24))));
    }

    @Test
    void initialCoteDIvoireReportIsTheOneTransmitted() {
        PasteResult r = service.importPaste(new PasteRequest("CI", CardAgencyStatusService.CI_INITIAL_REPORT, null), "QA");
        assertEquals(4, r.imported());
        assertEquals(List.of("NIANGON", "AGHIEN", "BOUAKE", "BEL AIR"), saved.stream().map(CardAgencyStatus::getAgency).toList());
    }

    @Test
    void statusesAreNormalizedAndUnknownOnesReported() {
        assertEquals("RUPTURE", CardAgencyStatusService.status("ko"));
        assertEquals("FAIBLE", CardAgencyStatusService.status("Stock faible"));
        assertEquals("OK", CardAgencyStatusService.status("disponible"));
        PasteResult r = service.importPaste(new PasteRequest("CI", "25/09/2026\tPLATEAU K01\tKO\tOK\tGOLD\nYOP K05\tpeut-être\tOK", null), "QA");
        assertEquals(1, r.imported());
        assertEquals("RUPTURE", saved.get(0).getCardStatus());
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void reportShowsTheLatestUpdateOfEveryAgencyByDefault() {
        CardAgencyStatus a = CardAgencyStatus.builder().countryCode("CI").reportDate(LocalDate.of(2026, 9, 24)).agency("NIANGON").cardStatus("OK").pinStatus("OK").cardTypes("GOLD, MX").build();
        CardAgencyStatus b = CardAgencyStatus.builder().countryCode("CI").reportDate(LocalDate.of(2026, 9, 20)).agency("AGHIEN").cardStatus("RUPTURE").pinStatus("OK").cardTypes("CLASSIC").build();
        when(repo.findByCountryCodeIgnoreCaseOrderByReportDateDescAgencyAsc("CI")).thenReturn(List.of(a, b));
        AgencyReport report = service.report("CI", null);
        assertEquals(LocalDate.of(2026, 9, 24), report.reportDate());
        assertTrue(report.current());
        assertEquals(2, report.rows().size()); // Aghien (point du 20/09) reste visible à côté de Niangon (24/09)
        assertEquals(List.of("CLASSIC", "GOLD", "MX"), report.allCardTypes());
        assertEquals(1, service.report("CI", LocalDate.of(2026, 9, 20)).rows().size());
    }
}

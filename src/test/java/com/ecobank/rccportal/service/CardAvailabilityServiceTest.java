package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CardAvailabilityDtos.CardRequest;
import com.ecobank.rccportal.dto.CardAvailabilityDtos.CellRequest;
import com.ecobank.rccportal.dto.CardAvailabilityDtos.MatrixResponse;
import com.ecobank.rccportal.model.BankBranch;
import com.ecobank.rccportal.model.CardAvailability;
import com.ecobank.rccportal.model.CardProduct;
import com.ecobank.rccportal.repository.BankBranchRepository;
import com.ecobank.rccportal.repository.CardAvailabilityRepository;
import com.ecobank.rccportal.repository.CardProductRepository;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Onglet « Disponibilité des cartes » : villes de la filiale, cochage QA, modèle standard. */
class CardAvailabilityServiceTest {

    private CardProductRepository cardRepository;
    private CardAvailabilityRepository availabilityRepository;
    private BankBranchRepository branchRepository;
    private CardAvailabilityService service;

    @BeforeEach
    void setUp() {
        cardRepository = mock(CardProductRepository.class);
        availabilityRepository = mock(CardAvailabilityRepository.class);
        branchRepository = mock(BankBranchRepository.class);
        BankBranchService branchService = mock(BankBranchService.class);
        when(branchService.normalizeCountryCode(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).trim().toUpperCase());
        service = new CardAvailabilityService(cardRepository, availabilityRepository, branchRepository, branchService);
        when(cardRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(availabilityRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void matrix_mergesBranchCitiesAndFilledCities_withoutDuplicates() {
        CardProduct visa = CardProduct.builder().id(1L).countryCode("SN").name("Visa Classic").build();
        when(cardRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderBySortOrderAscNameAsc("SN")).thenReturn(List.of(visa));
        when(branchRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderByCityAscNameAsc("SN")).thenReturn(List.of(
                BankBranch.builder().city("Dakar").build(), BankBranch.builder().city("dakar ").build(),
                BankBranch.builder().city("Thiès").build()));
        when(availabilityRepository.findByCardProductIdIn(List.of(1L))).thenReturn(List.of(
                CardAvailability.builder().cardProductId(1L).city("Ziguinchor").available(true).build(),
                CardAvailability.builder().cardProductId(1L).city("DAKAR").available(false).build()));

        MatrixResponse m = service.matrix("sn");

        assertEquals("SN", m.countryCode());
        assertEquals(List.of("Dakar", "Thiès", "Ziguinchor"), m.cities());
        assertEquals(1, m.cards().size());
        assertEquals(2, m.cells().size());
    }

    @Test
    void setCell_createsThenUpdatesTheSameCell() {
        when(cardRepository.findById(1L)).thenReturn(Optional.of(CardProduct.builder().id(1L).countryCode("SN").name("Visa").build()));
        when(availabilityRepository.findByCardProductIdAndCityIgnoreCase(1L, "Dakar")).thenReturn(Optional.empty());

        var cell = service.setCell(new CellRequest(1L, "  Dakar ", true, " stock faible "), "QA Test");

        assertTrue(cell.available());
        assertEquals("Dakar", cell.city());
        assertEquals("stock faible", cell.note());
        assertEquals("QA Test", cell.updatedBy());
    }

    @Test
    void setCell_rejectsUnknownCard() {
        when(cardRepository.findById(9L)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.setCell(new CellRequest(9L, "Dakar", true, null), "QA"));
    }

    @Test
    void addStandardCards_skipsCardsAlreadyPresent() {
        when(cardRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderBySortOrderAscNameAsc("CI"))
                .thenReturn(List.of(CardProduct.builder().id(1L).name("visa classic").build()));

        var created = service.addStandardCards("CI");

        assertEquals(CardAvailabilityService.STANDARD_CARDS.size() - 1, created.size());
        assertTrue(created.stream().noneMatch(c -> c.name().equalsIgnoreCase("Visa Classic")));
    }

    @Test
    void createCard_trimsAndNormalizesCountry() {
        ArgumentCaptor<CardProduct> saved = ArgumentCaptor.forClass(CardProduct.class);
        service.createCard(new CardRequest("ci", "  Visa Gold ", " ", "Délai 72h", null));
        verify(cardRepository).save(saved.capture());
        assertEquals("CI", saved.getValue().getCountryCode());
        assertEquals("Visa Gold", saved.getValue().getName());
        assertNull(saved.getValue().getCategory());
    }
}

package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.BankBranchCityResponse;
import com.ecobank.rccportal.dto.BankBranchRequest;
import com.ecobank.rccportal.model.BankBranch;
import com.ecobank.rccportal.model.KnowledgeCountry;
import com.ecobank.rccportal.repository.BankBranchRepository;
import com.ecobank.rccportal.repository.KnowledgeCountryRepository;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Onglet « Agences / Carte » : le code filiale envoyé par la Base de connaissance doit
 * retomber sur le code à 2 lettres stocké dans BankBranches, quel que soit son format.
 */
class BankBranchServiceTest {

    private BankBranchRepository branchRepository;
    private KnowledgeCountryRepository countryRepository;
    private BankBranchService service;

    @BeforeEach
    void setUp() {
        branchRepository = mock(BankBranchRepository.class);
        countryRepository = mock(KnowledgeCountryRepository.class);
        service = new BankBranchService(branchRepository, countryRepository);
        when(countryRepository.findAll()).thenReturn(List.of(
                KnowledgeCountry.builder().countryCode("CI").label("Côte d'Ivoire").sortOrder(1).build(),
                KnowledgeCountry.builder().countryCode("CD").label("République Démocratique du Congo").sortOrder(5).build()));
    }

    @Test
    void normalizeCountryCode_acceptsIso2Iso3AndKnowledgeBaseLabel() {
        assertEquals("CI", service.normalizeCountryCode("CI"));
        assertEquals("CI", service.normalizeCountryCode(" ci "));
        assertEquals("CI", service.normalizeCountryCode("CIV"));
        assertEquals("CI", service.normalizeCountryCode("civ"));
        assertEquals("CI", service.normalizeCountryCode("Côte d'Ivoire"));
        assertEquals("CI", service.normalizeCountryCode("cote d'ivoire"));
        assertEquals("CD", service.normalizeCountryCode("republique democratique du congo"));
        assertEquals("GW", service.normalizeCountryCode("GNB"));
        assertEquals("XYZW", service.normalizeCountryCode("xyzw")); // inconnu : liste vide, pas d'erreur
    }

    @Test
    void normalizeCountryCode_rejectsBlank() {
        assertThrows(ApiException.class, () -> service.normalizeCountryCode(" "));
        assertThrows(ApiException.class, () -> service.normalizeCountryCode(null));
    }

    @Test
    void listCitiesByCountry_queriesNormalizedCodeAndAveragesCoordinates() {
        when(branchRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderByCityAscNameAsc("CI")).thenReturn(List.of(
                branch("Abidjan", 5.0, -4.0),
                branch("Abidjan", 5.4, -4.2),
                branch("Aboisso", null, null)));

        List<BankBranchCityResponse> cities = service.listCitiesByCountry("CIV");

        verify(branchRepository).findByCountryCodeIgnoreCaseAndActiveTrueOrderByCityAscNameAsc("CI");
        assertEquals(2, cities.size());
        assertEquals("Abidjan", cities.get(0).city());
        assertEquals(2, cities.get(0).branchCount());
        assertEquals(5.2, cities.get(0).centerLatitude(), 1e-9);
        assertEquals(-4.1, cities.get(0).centerLongitude(), 1e-9);
        assertNull(cities.get(1).centerLatitude());
    }

    @Test
    void listByCountry_usesNormalizedCode() {
        when(branchRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderByCityAscNameAsc("CI"))
                .thenReturn(List.of(branch("Abidjan", 5.3, -4.0)));

        assertEquals(1, service.listByCountry("Côte d'Ivoire", false).size());
    }

    @Test
    void create_storesNormalizedCountryCode() {
        when(branchRepository.save(any(BankBranch.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(new BankBranchRequest("civ", " Abidjan ", " Agence Test ", null, 5.3, -4.0,
                null, null, null, null, "Agence", null));

        ArgumentCaptor<BankBranch> saved = ArgumentCaptor.forClass(BankBranch.class);
        verify(branchRepository).save(saved.capture());
        assertEquals("CI", saved.getValue().getCountryCode());
        assertEquals("Abidjan", saved.getValue().getCity());
        assertTrue(saved.getValue().isActive());
    }

    private static BankBranch branch(String city, Double lat, Double lon) {
        return BankBranch.builder().countryCode("CI").city(city).name("Agence " + city)
                .latitude(lat).longitude(lon).active(true).build();
    }
}

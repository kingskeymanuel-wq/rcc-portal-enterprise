package com.ecobank.rccportal.config;

import com.ecobank.rccportal.model.BankBranch;
import com.ecobank.rccportal.repository.BankBranchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * Amorce de la carte des agences : le vrai data/bank-branches.json doit se désérialiser
 * entièrement (sinon cartes vides) et une filiale déjà alimentée ne doit jamais être touchée.
 */
class BankBranchSeedBootstrapTest {

    @Test
    void run_seedsEveryBranchOfTheJsonWithCoordinates() {
        BankBranchRepository repository = mock(BankBranchRepository.class);
        when(repository.existsByCountryCodeIgnoreCase(anyString())).thenReturn(false);

        new BankBranchSeedBootstrap(repository, new ObjectMapper()).run();

        ArgumentCaptor<BankBranch> saved = ArgumentCaptor.forClass(BankBranch.class);
        verify(repository, times(29)).save(saved.capture());
        List<BankBranch> branches = saved.getAllValues();

        // Les 20 filiales de knowledge-countries.json, mêmes codes à 2 lettres.
        Set<String> countries = branches.stream().map(BankBranch::getCountryCode).collect(Collectors.toSet());
        assertEquals(Set.of("CI", "BF", "BJ", "BI", "CD", "CF", "CG", "CM", "CV", "GA",
                "GN", "GQ", "GW", "ML", "MZ", "NE", "SN", "ST", "TD", "TG"), countries);
        assertEquals(10, branches.stream().filter(b -> "CI".equals(b.getCountryCode())).count());
        assertTrue(branches.stream().allMatch(b -> b.getLatitude() != null && b.getLongitude() != null && b.isActive()));
    }

    @Test
    void run_skipsCountriesAlreadyFilled() {
        BankBranchRepository repository = mock(BankBranchRepository.class);
        when(repository.existsByCountryCodeIgnoreCase(anyString())).thenReturn(false);
        when(repository.existsByCountryCodeIgnoreCase("CI")).thenReturn(true);

        new BankBranchSeedBootstrap(repository, new ObjectMapper()).run();

        ArgumentCaptor<BankBranch> saved = ArgumentCaptor.forClass(BankBranch.class);
        // Filiale déjà remplie : seules les agences « ensure » absentes sont ajoutées (Niangon, Aghien, Bel Air, Bouaké).
        verify(repository, times(23)).save(saved.capture());
        List<String> ci = saved.getAllValues().stream().filter(b -> "CI".equals(b.getCountryCode())).map(BankBranch::getName).toList();
        assertEquals(List.of("Agence Niangon (K27)", "Agence Aghien (K10)", "Agence Yopougon Bel Air (K45)", "Agence Bouaké (K02)"), ci);
    }

    @Test
    void run_neverDuplicatesAnEnsuredBranch() {
        BankBranchRepository repository = mock(BankBranchRepository.class);
        when(repository.existsByCountryCodeIgnoreCase(anyString())).thenReturn(true);
        when(repository.existsByCountryCodeIgnoreCaseAndNameIgnoreCase(anyString(), anyString())).thenReturn(true);

        new BankBranchSeedBootstrap(repository, new ObjectMapper()).run();

        verify(repository, never()).save(any());
    }
}

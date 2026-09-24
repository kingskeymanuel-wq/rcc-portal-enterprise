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
        verify(repository, times(75)).save(saved.capture());
        List<BankBranch> branches = saved.getAllValues();

        // Les 20 filiales de knowledge-countries.json, mêmes codes à 2 lettres.
        Set<String> countries = branches.stream().map(BankBranch::getCountryCode).collect(Collectors.toSet());
        assertEquals(Set.of("CI", "BF", "BJ", "BI", "CD", "CF", "CG", "CM", "CV", "GA",
                "GN", "GQ", "GW", "ML", "MZ", "NE", "SN", "ST", "TD", "TG"), countries);
        assertEquals(56, branches.stream().filter(b -> "CI".equals(b.getCountryCode())).count());
        // Liste officielle des agences CI (K01…K57) : chaque code une seule fois.
        List<String> codes = branches.stream().map(b -> BankBranchSeedBootstrap.agencyCode(b.getName())).filter(java.util.Objects::nonNull).toList();
        assertEquals(56, codes.size());
        assertEquals(56, new java.util.HashSet<>(codes).size());
        assertTrue(branches.stream().allMatch(b -> b.getLatitude() != null && b.getLongitude() != null && b.isActive()));
    }

    @Test
    void run_upgradesAnExistingCountryWithoutDuplicates() {
        BankBranchRepository repository = mock(BankBranchRepository.class);
        when(repository.existsByCountryCodeIgnoreCase(anyString())).thenReturn(true);
        BankBranch aboisso = BankBranch.builder().countryCode("CI").city("Aboisso").name("Agence Aboisso").active(true).build();
        BankBranch ena = BankBranch.builder().countryCode("CI").city("Abidjan").name("Agence Cocody — ENA").active(true).build();
        BankBranch niangon = BankBranch.builder().countryCode("CI").city("Abidjan").name("Agence Yop Niangon (K27)").active(true).build();
        when(repository.findByCountryCodeIgnoreCaseOrderByCityAscNameAsc("CI")).thenReturn(new java.util.ArrayList<>(List.of(aboisso, ena, niangon)));

        new BankBranchSeedBootstrap(repository, new ObjectMapper()).run();

        ArgumentCaptor<BankBranch> saved = ArgumentCaptor.forClass(BankBranch.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        List<BankBranch> all = saved.getAllValues();
        assertEquals("Agence Aboisso (K24)", aboisso.getName());                 // renommée, pas recréée
        assertFalse(ena.isActive());                                            // hors liste officielle
        assertTrue(all.stream().noneMatch(b -> b != niangon && "K27".equals(BankBranchSeedBootstrap.agencyCode(b.getName()))));
        assertEquals(1, all.stream().filter(b -> "K24".equals(BankBranchSeedBootstrap.agencyCode(b.getName()))).count());
        // 56 agences officielles − Niangon déjà là − Aboisso renommée = 54 créations, + 1 renommage + 1 désactivation.
        assertEquals(56, all.size());
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

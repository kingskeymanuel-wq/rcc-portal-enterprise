package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HrOrganizationServiceTest {

    @Test
    void populationsFollowTheOrganisation() {
        assertEquals("OUTSOURCE", HrOrganizationService.inferPopulation("Outsource", "CDD", "CONSEILLER CLIENTELE", "INBOUND VOICE", List.of("AGENT_INBOUND")));
        assertEquals("STAGIAIRE", HrOrganizationService.inferPopulation("Outsource", "Stage", null, "RAFIKI", List.of()));
        assertEquals("STAGIAIRE", HrOrganizationService.inferPopulation(null, null, null, "OUTBOUND DIGITAL", List.of("AGENT_OUTBOUND")));
        assertEquals("STAFF", HrOrganizationService.inferPopulation("Ecobank", "CDI", "ANALYSTE QA", null, List.of("QUALITY_ASSURANCE")));
        assertEquals("STAFF", HrOrganizationService.inferPopulation(null, null, "Service opérations cartes", null, List.of()));
    }

    @Test
    void teamsAreLimitedToThePopulation() {
        assertEquals("VOICE", HrOrganizationService.inferTeam("OUTSOURCE", null, "INBOUND VOICE", List.of()));
        assertEquals("TCHAT", HrOrganizationService.inferTeam("OUTSOURCE", null, "INBOUND TCHAT", List.of()));
        assertEquals("MAIL", HrOrganizationService.inferTeam("OUTSOURCE", null, "INBOUND MAIL", List.of()));
        assertEquals("RAFIKI", HrOrganizationService.inferTeam("STAGIAIRE", null, "INBOUND MAIL/RAFIKI", List.of()));
        assertEquals("CIB", HrOrganizationService.inferTeam("OUTSOURCE", null, "CMB CIB", List.of()));
        assertEquals("DIGITAL", HrOrganizationService.inferTeam("STAGIAIRE", null, "OUTBOUND", List.of()));
        assertNull(HrOrganizationService.inferTeam("STAGIAIRE", null, "CIB", List.of()));      // pas de CIB chez les stagiaires → à affecter
        assertEquals("QA", HrOrganizationService.inferTeam("STAFF", "ANALYSTE", null, List.of("QUALITY_ASSURANCE")));
        assertEquals("CARD_OPS", HrOrganizationService.inferTeam("STAFF", "Opérations cartes", null, List.of()));
    }

    @Test
    void countriesAndRights() {
        assertEquals("CI", HrOrganizationService.country("CIV"));
        assertEquals("CI", HrOrganizationService.country(null));
        assertEquals("TG", HrOrganizationService.country("TGO"));
        assertEquals("TG", HrOrganizationService.normalizeCountry("tg"));
        assertThrows(RuntimeException.class, () -> HrOrganizationService.normalizeCountry("SN"));
        assertTrue(HrOrganizationService.canWrite(new AuthenticatedUser("rh", "RH", null, null)));
        assertTrue(HrOrganizationService.canRead(new AuthenticatedUser("s", "SUPERVISOR", null, null)));
        assertFalse(HrOrganizationService.canWrite(new AuthenticatedUser("s", "SUPERVISOR", null, null)));
        assertFalse(HrOrganizationService.canRead(new AuthenticatedUser("tl", "TEAM_LEADER", null, null)));
        assertEquals("INBOUND_MAIL", HrOrganizationService.businessTeam("RAFIKI"));
        assertEquals("OUTBOUND", HrOrganizationService.businessTeam("DIGITAL"));
    }

    @Test
    void performanceIsGroupedByPopulationAndTeam() {
        var yao = new HrOrganizationService.Person(1L, "yao", "Yao", null, "AGENT", "", "INBOUND VOICE", null, null, null, null, true, "CI", "OUTSOURCE", "VOICE", false, null);
        var ama = new HrOrganizationService.Person(2L, "ama", "Ama", null, "AGENT", "", "INBOUND VOICE", null, null, null, null, true, "CI", "OUTSOURCE", "VOICE", false, null);
        var zie = new HrOrganizationService.Person(3L, "zie", "Zié", null, "AGENT", "", "RAFIKI", null, null, null, null, true, "CI", "STAGIAIRE", "RAFIKI", false, null);
        Map<Long, HrOrganizationService.Person> byId = Map.of(1L, yao, 2L, ama, 3L, zie);
        List<PerformanceResponse> rows = List.of(
                new PerformanceResponse("yao", "Yao", "2026-09", 3, 80.0, Map.of(), 95, 82.0, "CI", null, null, 1L),
                new PerformanceResponse("ama", "Ama", "2026-09", 1, 70.0, Map.of(), 85, 74.0, "CI", null, null, 2L),
                new PerformanceResponse("zie", "Zié", "2026-09", 0, null, Map.of(), 99, null, "CI", null, null, 3L));
        var teams = HrOrganizationService.buildPerformance(rows, byId);
        assertEquals(11, teams.size());                                     // 5 + 4 + 2 équipes, même vides
        var voice = teams.stream().filter(t -> t.population().equals("OUTSOURCE") && t.team().equals("VOICE")).findFirst().orElseThrow();
        assertEquals(2, voice.headcount());
        assertEquals(90.0, voice.presence());
        assertEquals(75.0, voice.qualityScore());
        assertEquals(78.0, voice.performance());
        assertEquals(4, voice.evaluations());
        assertEquals("INBOUND_VOICE", voice.businessTeam());
        var rafiki = teams.stream().filter(t -> t.population().equals("STAGIAIRE") && t.team().equals("RAFIKI")).findFirst().orElseThrow();
        assertEquals(1, rafiki.headcount());
        assertNull(rafiki.qualityScore());
    }
}

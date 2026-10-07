package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.TeamClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Correctif Inbound Voix d'octobre 2026 : fichier de planning embarqué, journal et libellé « Réseaux sociaux ». */
class DataPatchServiceTest {

    private static byte[] csv() throws Exception {
        try (var in = new ClassPathResource(DataPatchService.PLANNING_FILE).getInputStream()) { return in.readAllBytes(); }
    }

    @Test
    void planningFileListsThe32InboundVoiceAgents() throws Exception {
        List<String> names = DataPatchService.planningNames(csv());
        assertEquals(32, names.size());
        assertEquals("ABODOU APO ROSELINE VINCENT", names.get(0));
        assertEquals("ASSOUMAN N'GORAN CLARISSE", names.get(3));
        assertEquals("KEBEY GHISLAIN LANDRY", names.get(13));
        assertEquals("ZOUGOULA BI IRIE RANGHINO", names.get(31));
        assertEquals("KOUAME KOUADIO JEAN", ScheduleService.splitNameAnnotation(names.get(22))[0], "mention « Aghien » retirée");
    }

    @Test
    void planningFileMatchesTheTotalsOfTheExceliamSheet() throws Exception {
        String[] lines = new String(csv(), StandardCharsets.UTF_8).split("\\R");
        assertTrue(lines[1].contains("Thu 01") && lines[1].endsWith("Sat 31"), "octobre 2026 : 1er jeudi, 31 jours");
        int[] shifts = new int[31];
        int agents = 0;
        for (String l : lines) {
            if (!l.matches("^\\d+,.*")) continue;
            agents++;
            String[] c = l.replaceAll("\"[^\"]*\"", "x").split(",");
            assertEquals(33, c.length, l);
            for (int d = 0; d < 31; d++) if (List.of("M", "M2", "M3", "M4", "A", "N").contains(c[d + 2])) shifts[d]++;
        }
        assertEquals(32, agents);
        int[] expected = {20, 21, 16, 15, 22, 25, 25, 24, 23, 16, 16, 23, 25, 25, 25, 24, 17, 16, 23, 25, 25, 25, 24, 17, 16, 23, 25, 25, 25, 24, 17};
        assertArrayEquals(expected, shifts, "ligne TOTAL SHIFTS du planning Exceliam");
    }

    @Test
    void inboundMailPlanningListsThe13AgentsWithTheirShifts() throws Exception {
        byte[] csv;
        try (var in = new ClassPathResource(DataPatchService.MAIL_PLANNING_FILE).getInputStream()) { csv = in.readAllBytes(); }
        List<String> names = DataPatchService.planningNames(csv);
        assertEquals(13, names.size());
        assertEquals("ANGUETE OHOULO CHRISTELLE", names.get(0), "mention « (ESPAGNOL) » retirée du nom");
        assertEquals("ZOUHO GERARD", names.get(6));
        assertEquals("TIERO HULDA CHANCE EUNICE", names.get(12));
        String[] lines = new String(csv, StandardCharsets.UTF_8).split("\\R");
        assertTrue(lines[1].contains("Thu 01") && lines[1].endsWith("Sat 31"), "octobre 2026 : 1er jeudi, 31 jours");
        int[] shifts = new int[31];
        for (String l : lines) {
            if (!l.matches("^\\d+,.*")) continue;
            String[] c = l.replaceAll("\"[^\"]*\"", "x").split(",");
            assertEquals(33, c.length, l);
            for (int d = 0; d < 31; d++) if (List.of("M", "M2", "M3", "A", "N").contains(c[d + 2])) shifts[d]++;
        }
        // Ligne TOTAL SHIFTS du fichier (le 30 : 9 et non 8, le total M3 du fichier ayant un 0 saisi à la place de la formule).
        int[] expected = {12, 10, 8, 5, 5, 10, 12, 11, 9, 8, 5, 5, 10, 12, 11, 9, 8, 5, 5, 10, 12, 11, 9, 8, 5, 5, 10, 12, 11, 9, 8};
        assertArrayEquals(expected, shifts);
    }

    @Test
    void journalLabels() {
        assertEquals("Rôle ajouté", AdminChangeJournal.label("POST", "/roles"));
        assertEquals("Service retiré", AdminChangeJournal.label("DELETE", "/services/4"));
        assertEquals("Accès modifié", AdminChangeJournal.label("PUT", "/access"));
        assertEquals("Fiche modifiée", AdminChangeJournal.label("PUT", null));
        assertTrue(AdminChangeJournal.isAdministrationChange("PUT", "/api/admin/users/3/access"));
        assertTrue(AdminChangeJournal.isAdministrationChange("POST", "/api/users/3/disable"));
        assertFalse(AdminChangeJournal.isAdministrationChange("GET", "/api/admin/hierarchy"));
        assertFalse(AdminChangeJournal.isAdministrationChange("POST", "/api/shift/me/event"));
    }

    @Test
    void socialNetworksIsTheFormerTchatChannel() {
        assertEquals("TCHAT", TeamClassifier.channel("Réseaux sociaux", List.of()));
        assertEquals(TeamClassifier.Team.INBOUND_MAIL, TeamClassifier.classify("RESEAUX SOCIAUX"));
        assertEquals("TEAM_LEADER_TCHAT", AdministrationService.serviceForRoleName("Team Leader Réseaux sociaux"));
        assertEquals("TEAM_LEADER_INBOUND_VOICE", AdministrationService.serviceForRoleName("Team Leader Inbound Voice"));
    }
}

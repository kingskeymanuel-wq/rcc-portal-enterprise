package com.ecobank.rccportal.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Dispatching : un fichier consolidé de plusieurs équipes est réparti agent par agent. */
class TeamPerfDispatchTest {

    private static void row(Sheet s, int r, Object... cells) {
        Row row = s.createRow(r);
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] instanceof Number n) row.createCell(i).setCellValue(n.doubleValue());
            else row.createCell(i).setCellValue(String.valueOf(cells[i]));
        }
    }

    /** Lignes de l'agent telles que lues par chaque équipe, puis équipe retenue. */
    private static String[] dispatch(Workbook wb, String agent, String portalTeam) {
        Map<String, TeamPerfFileService.AgentLine> byTeam = new LinkedHashMap<>();
        for (TeamPerfFileService.TeamDef t : TeamPerfFileService.CATALOG.values()) {
            try {
                for (TeamPerfFileService.AgentLine l : TeamPerfFileService.parse(wb, t, LocalDate.of(2026, 10, 9)).lines()) {
                    if (l.agentName().equals(agent)) byTeam.put(t.code(), l);
                }
            } catch (RuntimeException noColumns) {
                // équipe absente du fichier
            }
        }
        return TeamPerfFileService.assignTeam(byTeam, null, portalTeam);
    }

    @Test
    void mixedTableIsSplitByTheIndicatorsEachAgentFills() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("RCC");
            row(s, 0, "PERFORMANCES AGENTS RCC du 21 - 27 Septembre 2026");
            row(s, 1, "Agent", "Nombre de jours travaillés", "Emails traités", "CIS traités", "Sollicitations", "Appels sortants", "Réseaux sociaux", "Target", "Productivité");
            row(s, 2, "KA DJENABA", 5, 120, 40, 15, 0, 0, 30, 1.16);
            row(s, 3, "KONATE SOUMAILA", 5, 0, 0, 0, 206, 0, 40, 1.03);
            row(s, 4, "LASSEY CARMEN", 5, 0, 0, 0, 0, 310, 300, 1.03);
            assertEquals("INBOUND_MAIL", dispatch(wb, "KA DJENABA", null)[0], "mails, CIS et sollicitations : Inbound Mail");
            assertEquals("TCHAT", dispatch(wb, "LASSEY CARMEN", null)[0], "colonne Réseaux sociaux");
            // « Appels sortants » existe aussi côté Inbound Mail : l'équipe du compte départage.
            assertNull(dispatch(wb, "KONATE SOUMAILA", null)[0], "sans compte : à rattacher");
            assertEquals("OUTBOUND", dispatch(wb, "KONATE SOUMAILA", "OUTBOUND")[0]);
        }
    }

    /** Équipe indiquée dans le fichier (colonne ou titre de section), lue comme le fait le dispatching. */
    private static String fileTeam(Workbook wb, String agent) {
        for (TeamPerfFileService.TeamDef t : TeamPerfFileService.CATALOG.values()) {
            try {
                String h = TeamPerfFileService.parse(wb, t, LocalDate.of(2026, 10, 9)).hints().get(TeamPerfFileService.nameKey(agent));
                if (h != null) return TeamPerfFileService.teamInText(h);
            } catch (RuntimeException noColumns) {
                // équipe absente du fichier
            }
        }
        return null;
    }

    @Test
    void teamWrittenInTheFileWinsEvenWithoutIndicators() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("RCC");
            row(s, 0, "PERFORMANCES AGENTS RCC du 21 - 27 Septembre 2026");
            row(s, 1, "Agent", "Jours travaillés", "Appels sortants", "Target", "Productivité");
            row(s, 2, "TEAM INBOUND DIGITAL MAIL / CIS");
            row(s, 3, "DIAMBRA Sopie Audrey", 5, 0, 30, 1.0);
            row(s, 4, "TEAM OUTBOUND");
            row(s, 5, "KONATE SOUMAILA", 5, 206, 40, 1.03);
            assertEquals("INBOUND_MAIL", fileTeam(wb, "DIAMBRA Sopie Audrey"), "titre de section");
            assertEquals("OUTBOUND", fileTeam(wb, "KONATE SOUMAILA"));
            assertEquals("INBOUND_MAIL", dispatchWithFileTeam(wb, "DIAMBRA Sopie Audrey")[0], "aucun indicateur propre : l'équipe du fichier suffit");
        }
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("RCC");
            row(s, 0, "Agent", "Équipe", "Jours travaillés", "Target", "Productivité");
            row(s, 1, "KA Djeneba", "Mail", 5, 30, 0.9);
            row(s, 2, "ABODOU ROSELINE", "Inbound Voix", 5, 80, 1.1);
            assertEquals("INBOUND_MAIL", fileTeam(wb, "KA Djeneba"), "colonne Équipe");
            assertEquals("INBOUND_VOICE", fileTeam(wb, "ABODOU ROSELINE"));
        }
        assertNull(TeamPerfFileService.teamInText("PERFORMANCES AGENTS INBOUND MAIL ET OUTBOUND"), "titre de plusieurs équipes : rien d'imposé");
        assertEquals("INBOUND_MAIL", TeamPerfFileService.teamInText("TEAM INBOUND DIGITAL MAIL"));
    }

    private static String[] dispatchWithFileTeam(Workbook wb, String agent) {
        Map<String, TeamPerfFileService.AgentLine> byTeam = new LinkedHashMap<>();
        for (TeamPerfFileService.TeamDef t : TeamPerfFileService.CATALOG.values()) {
            try {
                for (TeamPerfFileService.AgentLine l : TeamPerfFileService.parse(wb, t, LocalDate.of(2026, 10, 9)).lines()) {
                    if (l.agentName().equals(agent)) byTeam.put(t.code(), l);
                }
            } catch (RuntimeException noColumns) {
                // équipe absente du fichier
            }
        }
        return TeamPerfFileService.assignTeam(byTeam, fileTeam(wb, agent), null);
    }

    @Test
    void teamViewsReadTheRightPerformanceFiles() {
        assertEquals(java.util.List.of("INBOUND_MAIL", "TCHAT", "RAFIKI"), TeamPerfFileService.fileTeamsForView("INBOUND_MAIL", null),
                "le pôle Inbound Mail voit aussi ses canaux");
        assertEquals(java.util.List.of("TCHAT"), TeamPerfFileService.fileTeamsForView("INBOUND_MAIL", "TCHAT"));
        assertEquals(java.util.List.of("OUTBOUND"), TeamPerfFileService.fileTeamsForView("OUTBOUND", "TELEVENTE"),
                "Télévente et Digitalisation : fichier Outbound (avant : aucun fichier lu)");
        assertEquals(java.util.List.of("INBOUND_VOICE"), TeamPerfFileService.fileTeamsForView("INBOUND_VOICE", null));
        var mail = new TeamPerfFileService.Aggregate(Map.of(1L, Map.of("productivity", 110.0)), Map.of(), Map.of());
        var chat = new TeamPerfFileService.Aggregate(Map.of(2L, Map.of("productivity", 95.0)), Map.of("kone ali", Map.of("productivity", 80.0)),
                Map.of("kone ali", "KONE Ali"));
        var all = TeamPerfFileService.Aggregate.merge(java.util.List.of(mail, chat));
        assertEquals(110.0, all.find(1L, null).get("productivity"));
        assertEquals(95.0, all.find(2L, null).get("productivity"));
        assertEquals("KONE Ali", all.names().get("kone ali"), "ligne du fichier sans compte : affichée sous son nom");
    }

    @Test
    void oneTablePerTeamIsDispatchedToEachTeam() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet mail = wb.createSheet("Inbound Mail");
            row(mail, 0, "Agent", "Jours travaillés", "Mails assistés", "CIS", "Sollicitations", "Target");
            row(mail, 1, "OSSOUE MARCELLE", 5, 140, 30, 12, 30);
            Sheet voix = wb.createSheet("Inbound Voix");
            row(voix, 0, "Agent", "Jours travaillés", "Appels traités", "DMT", "Target");
            row(voix, 1, "ABODOU ROSELINE", 5, 420, "0:03:10", 80);
            assertEquals("INBOUND_MAIL", dispatch(wb, "OSSOUE MARCELLE", null)[0]);
            assertEquals("INBOUND_VOICE", dispatch(wb, "ABODOU ROSELINE", null)[0]);
        }
    }
}

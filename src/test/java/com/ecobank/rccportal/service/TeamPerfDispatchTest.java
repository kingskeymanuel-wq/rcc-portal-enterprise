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
        return TeamPerfFileService.assignTeam(byTeam, portalTeam);
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

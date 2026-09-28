package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.security.AuthenticatedUser;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Rapport hebdo « Performances MAILS — du 21 - 27 Septembre » (capture fournie par la QA). */
class TeamPerfFileServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    private static final String[] HEADER = {"", "JOURS TRAVAILLES", "APPELS SORTANT", "CIS", "MAILS ASSIST", "SOLLICITATIONS", "TOTAL ACTIVITES",
            "MOY / JOUR", "TARGET / JOUR", "PRODUCTIVITE", "QUALITE", "ANCIENNETE"};

    private static final Object[][] ROWS = {
            {"Hulda Chance Eunice TIERO", 4, 2, 320, 165, 6, 491, 123, 70, "175%", "", "6 mois"},
            {"Ama Kra Noelle Paule Arielle KOUAME", 5, 35, "", 396, 47, 443, 89, 70, "127%", "", "6 mois"},
            {"MOKE Marie Carmella", 5, 33, "", 461, 97, 558, 112, 90, "124%", "", "3 ans"},
            {"Kibindza KOMBO", 5, "", "", 410, 7, 417, 83, 90, "93%", "", "8 ans"},
            {"ANGUETE Ohoulo Joelle Christelle", 5, "", 131, 261, 8, 400, 80, 90, "89%", "", ""},
            {"OSSOUE Marcelle Banahi", 3, 1, "", 159, 2, 161, 54, 90, "60%", "", ">10 ans"},
            {"Total général", "", 297, 545, 4329, 264, 5138, 83, 90, "", "", ""}};

    private static Workbook capture() {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("Mails");
        s.createRow(0).createCell(0).setCellValue("Performances MAILS — du 21 - 27 Septembre");
        s.createRow(1).createCell(0).setCellValue("PERFORMANCES MAILS");
        Row h = s.createRow(2);
        for (int c = 0; c < HEADER.length; c++) h.createCell(c).setCellValue(HEADER[c]);
        for (int r = 0; r < ROWS.length; r++) {
            Row row = s.createRow(3 + r);
            for (int c = 0; c < ROWS[r].length; c++) {
                Object v = ROWS[r][c];
                if (v instanceof Integer i) row.createCell(c).setCellValue(i);
                else if (!"".equals(v)) row.createCell(c).setCellValue((String) v);
            }
        }
        return wb;
    }

    private static TeamPerfFileService.AgentLine line(TeamPerfFileService.Parsed p, String name) {
        return p.lines().stream().filter(l -> l.agentName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void readsTheWeeklyMailReport() {
        var p = TeamPerfFileService.parse(capture(), TeamPerfFileService.team("INBOUND_MAIL"), TODAY);
        assertEquals(6, p.lines().size());                                  // la ligne « Total général » est écartée
        assertEquals(LocalDate.of(2026, 9, 21), p.from());
        assertEquals(LocalDate.of(2026, 9, 27), p.to());
        assertEquals(11, p.recognized().size());                            // toutes les colonnes de l'équipe

        var hulda = line(p, "Hulda Chance Eunice TIERO").values();
        assertEquals(4.0, hulda.get("daysWorked"));
        assertEquals(320.0, hulda.get("cis"));
        assertEquals(491.0, hulda.get("totalActivities"));
        assertEquals(175.0, hulda.get("productivity"));
        assertEquals("6 mois", hulda.get("seniority"));
        assertEquals("GOOD", line(p, "Hulda Chance Eunice TIERO").level());
        assertEquals("WARN", line(p, "Kibindza KOMBO").level());           // 93 % : orange
        assertEquals("BAD", line(p, "OSSOUE Marcelle Banahi").level());    // 60 % : rouge
        assertTrue(line(p, "ANGUETE Ohoulo Joelle Christelle").notes().isEmpty());
    }

    @Test
    void computesMissingIndicatorsLikeTheReport() {
        var t = TeamPerfFileService.team("INBOUND_MAIL");
        Map<String, Object> v = new LinkedHashMap<>(Map.of("daysWorked", 5.0, "mails", 396.0, "requests", 47.0, "targetPerDay", 70.0));
        TeamPerfFileService.complete(t, v, new ArrayList<>());
        assertEquals(443.0, v.get("totalActivities"));                      // CIS + Mails assistés + Sollicitations (appels sortants exclus)
        assertEquals(88.6, v.get("avgPerDay"));
        assertEquals(127.0, v.get("productivity"));
    }

    @Test
    void percentFormattedCellsAndPowerPointTablesAreRead() throws Exception {
        Workbook wb = capture();
        CellStyle pct = wb.createCellStyle();
        pct.setDataFormat(wb.createDataFormat().getFormat("0%"));
        Cell c = wb.getSheetAt(0).getRow(3).getCell(9);
        c.setCellValue(1.75);
        c.setCellStyle(pct);
        assertEquals(175.0, line(TeamPerfFileService.parse(wb, TeamPerfFileService.team("INBOUND_MAIL"), TODAY), "Hulda Chance Eunice TIERO").values().get("productivity"));

        XMLSlideShow show = new XMLSlideShow();
        var slide = show.createSlide();
        slide.createTextBox().setText("Performances MAILS — du 21 - 27 Septembre");
        XSLFTable table = slide.createTable();
        XSLFTableRow h = table.addRow();
        for (String s : HEADER) h.addCell().setText(s);
        for (Object[] r : ROWS) {
            XSLFTableRow row = table.addRow();
            for (Object v : r) row.addCell().setText(String.valueOf(v));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        show.write(out);
        var read = KpiFileReader.read(out.toByteArray(), "Rapport Weekly RCC.pptx", null);
        var p = TeamPerfFileService.parse(read.workbook(), TeamPerfFileService.team("INBOUND_MAIL"), TODAY);
        assertEquals(6, p.lines().size());
        assertEquals(LocalDate.of(2026, 9, 21), p.from());
        assertEquals(558.0, line(p, "MOKE Marie Carmella").values().get("totalActivities"));
    }

    @Test
    void detectsOtherPeriodWritings() {
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)},
                TeamPerfFileService.detectPeriod("Semaine du 28 septembre au 4 octobre", TODAY));
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27)},
                TeamPerfFileService.detectPeriod("Période 21/09 - 27/09/2026", TODAY));
        assertNull(TeamPerfFileService.detectPeriod("PERFORMANCES MAILS", TODAY));
    }

    private static User user(long id, String username, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setName(name);
        return u;
    }

    @Test
    void agentsAreMatchedDespiteNameOrderAndMissingFirstNames() {
        List<User> all = List.of(user(1, "htiero", "TIERO Hulda"), user(2, "mmoke", "Moké Marie Carmella"), user(3, "kkombo", "KOMBO Kibindza"),
                user(4, "akouame", "KOUAME Ama"), user(5, "bkouame", "KOUAME Ama Bintou"));
        assertEquals(1L, TeamPerfFileService.matchUser("Hulda Chance Eunice TIERO", all).getId());
        assertEquals(2L, TeamPerfFileService.matchUser("MOKE Marie Carmella", all).getId());
        assertEquals(3L, TeamPerfFileService.matchUser("Kibindza KOMBO", all).getId());
        assertEquals(4L, TeamPerfFileService.matchUser("Ama Kra Noelle Paule Arielle KOUAME", all).getId());
        assertNull(TeamPerfFileService.matchUser("Gerard ZOUHO", all));
    }

    @Test
    void onlyQualityTeamsImport() {
        assertTrue(TeamPerfFileService.canImport(new AuthenticatedUser("q", "QA", "QUALITY_ASSURANCE", null)));
        assertTrue(TeamPerfFileService.canImport(new AuthenticatedUser("h", "QA_SUPERVISOR", "SUPERVISEUR_QA", null)));
        assertFalse(TeamPerfFileService.canImport(new AuthenticatedUser("a", "AGENT", null, null)));
        assertFalse(TeamPerfFileService.canImport(new AuthenticatedUser("t", "TEAM_LEADER", null, null)));
    }
}

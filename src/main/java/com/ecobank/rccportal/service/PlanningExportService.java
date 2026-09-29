package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AgentScheduleResponse;
import com.ecobank.rccportal.dto.UserDirectoryResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Export Excel du planning d'une équipe, dans la même grille que le planning Exceliam (une ligne par agent,
 * une colonne « Thu 01 » par jour, codes de shift colorés, lignes « TOTAL M (07H - 16H) ») : le fichier se relit
 * tel quel par l'import du planning. Un Team Leader n'exporte que SON équipe (Tchat / Rafiki compris),
 * la QA, le RH, le Superviseur et l'administrateur choisissent l'équipe.
 */
@Service
public class PlanningExportService {

    /** Ordre des lignes TOTAL et couleurs des codes (mêmes teintes que le planning Exceliam). */
    private static final Map<String, String> CODE_COLORS = new LinkedHashMap<>();

    static {
        CODE_COLORS.put("M", "BFD8F5");
        CODE_COLORS.put("M2", "8FB8EC");
        CODE_COLORS.put("M3", "6FA0E0");
        CODE_COLORS.put("M4", "2F5FA8");
        CODE_COLORS.put("A", "8FE0C2");
        CODE_COLORS.put("N", "1E2761");
        CODE_COLORS.put("C", "8FCB82");
        CODE_COLORS.put("RM", "F3B15C");
        CODE_COLORS.put("OFF", "E4E7EF");
    }

    private static final Map<String, LocalTime[]> DEFAULT_HOURS = Map.of(
            "M", new LocalTime[]{LocalTime.of(7, 0), LocalTime.of(16, 0)}, "M2", new LocalTime[]{LocalTime.of(8, 0), LocalTime.of(17, 0)},
            "M3", new LocalTime[]{LocalTime.of(9, 0), LocalTime.of(18, 0)}, "M4", new LocalTime[]{LocalTime.of(10, 0), LocalTime.of(19, 0)},
            "A", new LocalTime[]{LocalTime.of(12, 0), LocalTime.of(21, 0)}, "N", new LocalTime[]{LocalTime.of(21, 0), LocalTime.of(7, 0)});

    private final ScheduleService schedules;
    private final TeamLeaderService teamLeaders;

    public PlanningExportService(ScheduleService schedules, TeamLeaderService teamLeaders) {
        this.schedules = schedules;
        this.teamLeaders = teamLeaders;
    }

    public record Export(String filename, byte[] content) {}

    public Export export(AuthenticatedUser requester, String team, LocalDate from, LocalDate to) {
        if (requester == null) throw ApiException.unauthorized("Non connecté.");
        if ("AGENT".equalsIgnoreCase(requester.role()) && !isQa(requester)) {
            throw ApiException.forbidden("L'export du planning est réservé aux Team Leaders, à la QA, au RH et au Superviseur.");
        }
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("Période invalide.");
        if (ChronoUnit.DAYS.between(from, to) > 62) throw ApiException.badRequest("Période trop longue : 2 mois au maximum par export.");

        boolean teamLeader = "TEAM_LEADER".equalsIgnoreCase(requester.role());
        String teamLabel;
        List<String[]> agents = new ArrayList<>(); // [username, nom]
        if (teamLeader) {
            String code = teamLeaders.ledTeamCode(requester);
            teamLabel = label(code);
            for (UserDirectoryResponse u : teamLeaders.teamMembers(requester)) {
                if (Boolean.FALSE.equals(u.active())) continue;
                agents.add(new String[]{u.username(), u.fullName() != null ? u.fullName() : u.username()});
            }
        } else {
            teamLabel = team == null || team.isBlank() ? "Toutes les équipes" : label(team);
        }
        List<AgentScheduleResponse> entries = schedules.planningForTeamScoped(requester, teamLeader ? null : team, from, to);
        Map<String, Map<LocalDate, AgentScheduleResponse>> byAgent = new LinkedHashMap<>();
        for (AgentScheduleResponse e : entries) {
            if (e.username() == null) continue;
            byAgent.computeIfAbsent(e.username().toLowerCase(Locale.ROOT), k -> new HashMap<>()).put(e.workDate(), e);
            if (agents.stream().noneMatch(a -> a[0].equalsIgnoreCase(e.username()))) {
                agents.add(new String[]{e.username(), e.fullName() != null ? e.fullName() : e.username()});
            }
        }
        agents.sort(Comparator.comparing(a -> a[1], String.CASE_INSENSITIVE_ORDER));
        byte[] content = workbook(teamLabel, from, to, agents, byAgent);
        String filename = "planning_" + teamLabel.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "") + "_" + from + "_" + to + ".xlsx";
        return new Export(filename, content);
    }

    private static boolean isQa(AuthenticatedUser u) {
        String s = u.service() == null ? "" : u.service().toUpperCase(Locale.ROOT).replace(' ', '_');
        return s.equals("QUALITY_ASSURANCE") || s.equals("SUPERVISEUR_QA");
    }

    static String label(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (c) {
            case "INBOUND_VOICE" -> "Inbound Voix";
            case "INBOUND_MAIL" -> "Inbound Mail";
            case "TCHAT" -> "Tchat";
            case "RAFIKI" -> "Rafiki";
            case "OUTBOUND" -> "Outbound";
            case "CIB" -> "CIB";
            default -> code == null ? "" : code;
        };
    }

    /** Grille : titre, en-tête « Thu 01 », une ligne numérotée par agent, lignes TOTAL par code et TOTAL SHIFTS. */
    static byte[] workbook(String teamLabel, LocalDate from, LocalDate to, List<String[]> agents,
                           Map<String, Map<LocalDate, AgentScheduleResponse>> byAgent) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) days.add(d);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Planning");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle title = wb.createCellStyle();
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title.setFont(big);
            CellStyle head = box(wb, "4AB3E2", bold);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setRotation((short) 90);
            CellStyle plain = box(wb, null, null);
            CellStyle team = box(wb, "4AB3E2", bold);
            team.setAlignment(HorizontalAlignment.CENTER);
            CellStyle totalLabel = box(wb, "8FC7E8", bold);
            CellStyle totalValue = box(wb, "92D050", null);
            totalValue.setAlignment(HorizontalAlignment.CENTER);
            CellStyle shiftsLabel = box(wb, "8FC7E8", bold);
            CellStyle shiftsValue = box(wb, "FFFF00", bold);
            shiftsValue.setAlignment(HorizontalAlignment.CENTER);
            Map<String, CellStyle> codeStyles = new HashMap<>();

            Row r0 = sh.createRow(0);
            r0.createCell(1).setCellValue("Planning " + teamLabel + " — du " + from + " au " + to);
            r0.getCell(1).setCellStyle(title);

            Row header = sh.createRow(2);
            header.setHeightInPoints(42);
            header.createCell(0).setCellStyle(head);
            header.createCell(1).setCellStyle(head);
            for (int i = 0; i < days.size(); i++) {
                LocalDate d = days.get(i);
                Cell c = header.createCell(2 + i);
                c.setCellValue(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + String.format("%02d", d.getDayOfMonth()));
                c.setCellStyle(head);
            }
            Row teamRow = sh.createRow(3);
            teamRow.createCell(1).setCellValue("Team " + teamLabel);
            teamRow.getCell(1).setCellStyle(team);
            if (days.size() > 0) sh.addMergedRegion(new CellRangeAddress(3, 3, 1, 1 + days.size()));

            Map<String, LocalTime[]> hours = new LinkedHashMap<>();
            for (String c : List.of("M", "M2", "M3", "M4", "A", "N")) hours.put(c, DEFAULT_HOURS.get(c));
            Map<String, int[]> counts = new LinkedHashMap<>();
            int rowIdx = 4;
            for (int a = 0; a < agents.size(); a++) {
                Row row = sh.createRow(rowIdx++);
                Cell num = row.createCell(0);
                num.setCellValue(a + 1);
                num.setCellStyle(plain);
                Cell name = row.createCell(1);
                name.setCellValue(agents.get(a)[1]);
                name.setCellStyle(plain);
                Map<LocalDate, AgentScheduleResponse> mine = byAgent.getOrDefault(agents.get(a)[0].toLowerCase(Locale.ROOT), Map.of());
                for (int i = 0; i < days.size(); i++) {
                    Cell c = row.createCell(2 + i);
                    AgentScheduleResponse e = mine.get(days.get(i));
                    if (e == null || e.shiftCode() == null) { c.setCellStyle(plain); continue; }
                    String code = e.shiftCode().trim().toUpperCase(Locale.ROOT);
                    c.setCellValue(code);
                    c.setCellStyle(codeStyles.computeIfAbsent(code, k -> codeStyle(wb, k, bold)));
                    if (e.startTime() != null && e.endTime() != null) hours.put(code, new LocalTime[]{e.startTime(), e.endTime()});
                    counts.computeIfAbsent(code, k -> new int[days.size()])[i]++;
                }
            }
            // Lignes TOTAL : la « légende » que l'import sait relire (horaires entre parenthèses).
            int[] shifts = new int[days.size()];
            for (Map.Entry<String, LocalTime[]> h : hours.entrySet()) {
                int[] n = counts.getOrDefault(h.getKey(), new int[days.size()]);
                Row row = sh.createRow(rowIdx++);
                Cell l = row.createCell(1);
                l.setCellValue("TOTAL " + h.getKey() + " (" + String.format("%02dH", h.getValue()[0].getHour()) + " - " + String.format("%02dH", h.getValue()[1].getHour()) + ")");
                l.setCellStyle(totalLabel);
                for (int i = 0; i < days.size(); i++) {
                    Cell c = row.createCell(2 + i);
                    c.setCellValue(n[i]);
                    c.setCellStyle(totalValue);
                    shifts[i] += n[i];
                }
            }
            Row total = sh.createRow(rowIdx);
            total.createCell(0).setCellValue("L");
            Cell tl = total.createCell(1);
            tl.setCellValue("TOTAL SHIFTS");
            tl.setCellStyle(shiftsLabel);
            for (int i = 0; i < days.size(); i++) {
                Cell c = total.createCell(2 + i);
                c.setCellValue(shifts[i]);
                c.setCellStyle(shiftsValue);
            }
            sh.setColumnWidth(0, 5 * 256);
            sh.setColumnWidth(1, 46 * 256);
            for (int i = 0; i < days.size(); i++) sh.setColumnWidth(2 + i, 6 * 256);
            sh.createFreezePane(2, 3);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Export du planning impossible : " + e.getMessage(), e);
        }
    }

    private static CellStyle box(XSSFWorkbook wb, String rgb, Font font) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        if (rgb != null) {
            s.setFillForegroundColor(new XSSFColor(HexFormat.of().parseHex(rgb), null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (font != null) s.setFont(font);
        return s;
    }

    private static CellStyle codeStyle(XSSFWorkbook wb, String code, Font bold) {
        CellStyle s = box(wb, CODE_COLORS.getOrDefault(code, "FFFFFF"), bold);
        s.setAlignment(HorizontalAlignment.CENTER);
        if (Set.of("M4", "N").contains(code)) {
            Font white = wb.createFont();
            white.setBold(true);
            white.setColor(IndexedColors.WHITE.getIndex());
            s.setFont(white);
        }
        return s;
    }
}

package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.TeamKpiImportResult;
import com.ecobank.rccportal.dto.TeamKpiSeriesResponse;
import com.ecobank.rccportal.model.TeamKpiEntry;
import com.ecobank.rccportal.repository.TeamKpiEntryRepository;
import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Import KPI au niveau équipe/pôle — pour les fichiers type "FOCUS PAR PÔLE" (confirmé sur
 * fichier réel : une ligne par métrique — CALLS OFFERED, AVERAGE HANDLE TIME... — une colonne
 * par mois, puis Q1-Q4, puis année précédente). Structurellement DIFFÉRENT du fichier KPI par
 * agent (ManualKpiEntryService) : ce type de fichier NE CONTIENT AUCUN AGENT — tenter d'y
 * rattacher un agent serait fabriquer une donnée absente du fichier source.
 *
 * Colonne "TARGET" (objectif, pas une valeur datée) volontairement exclue du chronologique et
 * comptée séparément dans le résultat — jamais confondue avec une vraie perte de donnée.
 */
@Service
public class TeamKpiImportService {

    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");
    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2}):(\\d{2})(?:\\s*([AaPp][Mm]))?$");

    private static final Map<String, Integer> MONTH_NAME_TO_NUMBER = buildMonthMap();

    private static Map<String, Integer> buildMonthMap() {
        Map<String, Integer> m = new HashMap<>();
        String[][] names = {
                {"JANV", "JAN", "JANVIER"}, {"FEVR", "FEV", "FEVRIER"}, {"MARS"}, {"AVR", "AVRIL"},
                {"MAI"}, {"JUIN"}, {"JUIL", "JUILLET"}, {"AOUT", "AOU"}, {"SEPT", "SEP", "SEPTEMBRE"},
                {"OCT", "OCTOBRE"}, {"NOV", "NOVEMBRE"}, {"DEC", "DECEMBRE"}
        };
        for (int i = 0; i < names.length; i++) {
            for (String alias : names[i]) m.put(alias, i + 1);
        }
        return m;
    }

    private final TeamKpiEntryRepository teamKpiEntryRepository;
    private final AuditLogService auditLogService;

    public TeamKpiImportService(TeamKpiEntryRepository teamKpiEntryRepository, AuditLogService auditLogService) {
        this.teamKpiEntryRepository = teamKpiEntryRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public TeamKpiImportResult importFromExcel(MultipartFile file, String team, String enteredByUsername) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Le fichier est requis.");
        if (team == null || team.isBlank()) throw ApiException.badRequest("L'équipe/le pôle est obligatoire — le fichier ne l'indique jamais lui-même.");

        String normalizedTeam = team.trim().toUpperCase();
        String importBatchId = java.util.UUID.randomUUID().toString();

        int metricsProcessed = 0;
        int valuesCreated = 0;
        int targetsSkipped = 0;
        int numericCellsDetected = 0;
        List<TeamKpiImportResult.SkippedValue> skippedValues = new ArrayList<>();

        boolean isCsv = file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase().endsWith(".csv");
        try (Workbook workbook = isCsv ? buildWorkbookFromCsv(file.getInputStream()) : WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            // Cherche la ligne d'en-tête : au moins 2 cellules reconnues comme nom de mois.
            int headerRowIndex = -1;
            List<Integer> monthColumns = new ArrayList<>(); // colonnes -> mois (1-12)
            Map<Integer, Integer> colToMonth = new LinkedHashMap<>();
            int yearColumn = -1; // colonne "année précédente" (ex. "2025")
            int currentYear = LocalDate.now().getYear();

            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 10); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<Integer, Integer> found = new LinkedHashMap<>();
                int yearCol = -1;
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    String text = stripAccents(cleanText(row.getCell(c)).toUpperCase()).replace(".", "").replace("-", " ").trim();
                    String monthKey = text.split(" ")[0];
                    if (MONTH_NAME_TO_NUMBER.containsKey(monthKey)) found.put(c, MONTH_NAME_TO_NUMBER.get(monthKey));
                    Matcher ym = YEAR_PATTERN.matcher(cleanText(row.getCell(c)));
                    if (ym.matches()) yearCol = c; // une cellule qui n'est QUE "2025" (année précédente)
                }
                if (found.size() >= 2) {
                    headerRowIndex = r;
                    colToMonth = found;
                    yearColumn = yearCol;
                    break;
                }
            }
            if (headerRowIndex == -1) {
                throw ApiException.badRequest("Aucune ligne d'en-tête avec des mois reconnue dans ce fichier — " +
                        "format inattendu pour un import KPI par pôle.");
            }

            // Colonne TARGET — juste après la colonne "métrique" (col 0), si son en-tête dit "TARGET".
            Row headerRow = sheet.getRow(headerRowIndex);
            boolean hasTargetColumn = cleanText(headerRow.getCell(1)).equalsIgnoreCase("TARGET");

            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String metricLabel = cleanText(row.getCell(0));
                if (metricLabel.isBlank()) continue; // ligne de séparation/titre

                String metricCode = sanitizeMetricCode(metricLabel);
                metricsProcessed++;

                // TARGET — valeur réelle mais non datée, volontairement exclue du chronologique.
                if (hasTargetColumn) {
                    Cell targetCell = row.getCell(1);
                    String targetText = cleanText(targetCell);
                    if (!targetText.isBlank() && cellToNumber(targetCell) != null) {
                        targetsSkipped++; // comptée séparément — ce n'est pas une perte, juste hors périmètre chronologique
                    }
                }

                for (Map.Entry<Integer, Integer> monthEntry : colToMonth.entrySet()) {
                    int col = monthEntry.getKey();
                    int month = monthEntry.getValue();
                    Cell cell = row.getCell(col);
                    String rawText = cleanText(cell);
                    if (rawText.isBlank()) continue; // colonne mois pas encore atteinte (ex. sept-26 en cours d'année) — pas une perte

                    numericCellsDetected++;
                    BigDecimal value = cellToNumber(cell);
                    if (value == null) {
                        skippedValues.add(new TeamKpiImportResult.SkippedValue(
                                metricLabel, monthLabel(month), rawText, "Valeur illisible comme nombre : « " + rawText + " »"));
                        continue;
                    }

                    LocalDate periodDate = YearMonth.of(currentYear, month).atDay(1);
                    teamKpiEntryRepository.save(TeamKpiEntry.builder()
                            .team(normalizedTeam).metricCode(metricCode).metricValue(value)
                            .periodDate(periodDate).enteredByUsername(enteredByUsername)
                            .importBatchId(importBatchId).build());
                    valuesCreated++;
                }

                // Colonne "année précédente" (ex. "2025") — capturée comme un point au 1er janvier
                // de cette année-là, avec le même code métrique suffixé _ANNEE_PRECEDENTE pour ne
                // jamais la confondre avec un vrai mois de l'année en cours.
                if (yearColumn != -1) {
                    Cell yearCell = row.getCell(yearColumn);
                    String rawText = cleanText(yearCell);
                    if (!rawText.isBlank()) {
                        numericCellsDetected++;
                        BigDecimal value = cellToNumber(yearCell);
                        if (value == null) {
                            skippedValues.add(new TeamKpiImportResult.SkippedValue(
                                    metricLabel, "(année précédente)", rawText, "Valeur illisible comme nombre : « " + rawText + " »"));
                        } else {
                            Integer previousYear = extractYear(cleanText(headerRow.getCell(yearColumn)));
                            LocalDate periodDate = previousYear != null
                                    ? LocalDate.of(previousYear, 1, 1) : LocalDate.of(currentYear - 1, 1, 1);
                            teamKpiEntryRepository.save(TeamKpiEntry.builder()
                                    .team(normalizedTeam).metricCode(metricCode + "_ANNEE_PRECEDENTE").metricValue(value)
                                    .periodDate(periodDate).enteredByUsername(enteredByUsername)
                                    .importBatchId(importBatchId).build());
                            valuesCreated++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw ApiException.badRequest("Impossible de lire le fichier : " + e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier invalide ou mal formé : " + e.getMessage());
        }

        String completenessNote = numericCellsDetected > 0
                ? Math.round((valuesCreated * 100.0) / numericCellsDetected) + "% capturé (" + valuesCreated + "/" + numericCellsDetected + " valeurs), "
                  + skippedValues.size() + " non capturée(s), " + targetsSkipped + " objectif(s) (TARGET) hors périmètre chronologique"
                : valuesCreated + " valeur(s) capturée(s)";
        auditLogService.record(enteredByUsername, "IMPORT_TEAM_KPI",
                "Fichier : " + (file.getOriginalFilename() != null ? file.getOriginalFilename() : "(sans nom)") +
                " — équipe " + normalizedTeam + " — " + completenessNote);

        return new TeamKpiImportResult(normalizedTeam, metricsProcessed, valuesCreated, targetsSkipped,
                importBatchId, numericCellsDetected, skippedValues);
    }

    /** Historique mensuel d'une métrique pour une équipe — pour l'affichage tendance. */
    @Transactional(readOnly = true)
    public TeamKpiSeriesResponse series(String team, String metricCode) {
        List<TeamKpiEntry> entries = teamKpiEntryRepository.findByTeamOrderByPeriodDateDesc(team.trim().toUpperCase());
        List<TeamKpiSeriesResponse.MonthlyPoint> points = entries.stream()
                .filter(e -> e.getMetricCode().equalsIgnoreCase(metricCode))
                .sorted(Comparator.comparing(TeamKpiEntry::getPeriodDate))
                .map(e -> new TeamKpiSeriesResponse.MonthlyPoint(YearMonth.from(e.getPeriodDate()).toString(), e.getMetricValue()))
                .toList();
        return new TeamKpiSeriesResponse(team.trim().toUpperCase(), metricCode.toUpperCase(), points);
    }

    /** Tous les codes métrique disponibles pour une équipe — pour peupler un sélecteur. */
    @Transactional(readOnly = true)
    public List<String> availableMetrics(String team) {
        return teamKpiEntryRepository.findByTeamOrderByPeriodDateDesc(team.trim().toUpperCase()).stream()
                .map(TeamKpiEntry::getMetricCode).distinct().sorted().toList();
    }

    @Transactional
    public long deleteImportBatch(String importBatchId) {
        return teamKpiEntryRepository.deleteByImportBatchId(importBatchId);
    }

    // ══════════════════════════════════════════════════════════════════════

    private Integer extractYear(String text) {
        Matcher m = YEAR_PATTERN.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private String monthLabel(int month) {
        String[] labels = {"", "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
                "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre"};
        return labels[month];
    }

    private String sanitizeMetricCode(String headerText) {
        return stripAccents(headerText.toUpperCase()).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private String stripAccents(String text) {
        return text
                .replace("É", "E").replace("È", "E").replace("Ê", "E").replace("é", "e").replace("è", "e").replace("ê", "e")
                .replace("À", "A").replace("Â", "A").replace("à", "a").replace("â", "a")
                .replace("Ô", "O").replace("ô", "o")
                .replace("Û", "U").replace("Ù", "U").replace("û", "u").replace("ù", "u")
                .replace("Î", "I").replace("Ï", "I").replace("î", "i").replace("ï", "i")
                .replace("Ç", "C").replace("ç", "c");
    }

    private String cleanText(Cell cell) {
        if (cell == null) return "";
        String raw = switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? "" : String.valueOf((long) cell.getNumericCellValue());
            default -> "";
        };
        return raw.replace("\u200b", "").replace("\u00a0", " ").trim();
    }

    private BigDecimal cellToNumber(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(cell)) {
                    java.time.LocalTime time = cell.getLocalDateTimeCellValue().toLocalTime();
                    return BigDecimal.valueOf(time.toSecondOfDay() / 60.0);
                }
                return BigDecimal.valueOf(cell.getNumericCellValue());
            }
            if (cell.getCellType() == CellType.STRING) {
                String text = cell.getStringCellValue().replace("\u200b", "").replace("\u00a0", "").trim();
                if (text.isBlank() || text.equalsIgnoreCase("N/A") || text.equals("#DIV/0!") || text.equals("-")) return null;
                // Pourcentage — "34%" -> 34 (pas 0.34, cohérent avec l'affichage attendu par l'agent).
                if (text.endsWith("%")) {
                    try { return new BigDecimal(text.substring(0, text.length() - 1).replace(",", ".").trim()); }
                    catch (NumberFormatException ignored) { return null; }
                }
                Matcher timeMatch = TIME_PATTERN.matcher(text);
                if (timeMatch.matches()) {
                    int h = Integer.parseInt(timeMatch.group(1));
                    int m = Integer.parseInt(timeMatch.group(2));
                    int s = Integer.parseInt(timeMatch.group(3));
                    String meridiem = timeMatch.group(4);
                    if (meridiem != null) {
                        if (meridiem.equalsIgnoreCase("AM") && h == 12) h = 0;
                        else if (meridiem.equalsIgnoreCase("PM") && h != 12) h += 12;
                    }
                    return BigDecimal.valueOf(h * 60 + m + s / 60.0);
                }
                // Nombres avec séparateur de milliers (" 64,404 ") — retire les espaces ET la virgule
                // de groupement, mais uniquement si elle ne ressemble pas à un séparateur décimal
                // (plus de 2 chiffres après la dernière virgule = c'est un séparateur de milliers).
                String cleaned = text.replace(" ", "");
                int lastComma = cleaned.lastIndexOf(',');
                if (lastComma != -1 && cleaned.length() - lastComma - 1 > 2) {
                    cleaned = cleaned.replace(",", "");
                } else {
                    cleaned = cleaned.replace(",", ".");
                }
                return new BigDecimal(cleaned);
            }
        } catch (NumberFormatException | ArithmeticException ignored) {
        }
        return null;
    }

    private Workbook buildWorkbookFromCsv(java.io.InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        List<String> lines = decodeCsvLines(bytes);
        if (lines.isEmpty()) throw ApiException.badRequest("Le fichier CSV est vide.");

        char delimiter = ',';
        org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Import");
        for (int r = 0; r < lines.size(); r++) {
            String[] fields = splitCsvLine(lines.get(r), delimiter);
            Row row = sheet.createRow(r);
            for (int c = 0; c < fields.length; c++) {
                Cell cell = row.createCell(c);
                cell.setCellValue(fields[c].trim());
            }
        }
        return workbook;
    }

    /** Détecte l'encodage — UTF-8 par défaut, repli ISO-8859-1 si des caractères de
     *  remplacement (U+FFFD) apparaissent, très courant sur les exports Excel français
     *  (confirmé sur le fichier réel testé : "CSV ISO-8859 text"). */
    private List<String> decodeCsvLines(byte[] bytes) {
        String utf8Text = new String(bytes, StandardCharsets.UTF_8);
        if (utf8Text.indexOf('\uFFFD') == -1) {
            return utf8Text.lines().toList();
        }
        return new String(bytes, java.nio.charset.Charset.forName("ISO-8859-1")).lines().toList();
    }

    private String[] splitCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') inQuotes = !inQuotes;
            else if (ch == delimiter && !inQuotes) { fields.add(current.toString()); current = new StringBuilder(); }
            else current.append(ch);
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}

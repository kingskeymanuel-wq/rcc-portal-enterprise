package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.LatenessResponse;
import com.ecobank.rccportal.dto.ScheduleImportResult;
import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.ShiftEvent;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserServiceAssignment;
import com.ecobank.rccportal.repository.AgentScheduleRepository;
import com.ecobank.rccportal.repository.ShiftEventRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Planning prévisionnel des agents — deux formats acceptés en import :
 * <ol>
 *   <li><b>Grille mensuelle par équipe</b> (voir parseWideFormat()) — une ligne par agent, une
 *       colonne par jour du mois, cellule = code de shift (M, M2, OFF, ABS, C...), avec une
 *       légende quelque part dans le fichier faisant correspondre chaque code à un horaire
 *       (ex. "M2" → "Matin 08h-17h") ou à un type d'absence sans horaire. C'est le format réel
 *       exporté par l'outil de planning RCC — format PRIORITAIRE, essayé en premier.</li>
 *   <li><b>Ancien format simple</b> (matricule, date, heure de début) — conservé en repli pour
 *       ne rien casser si un fichier à l'ancien format est réimporté.</li>
 * </ol>
 * Sert aussi à détecter les retards : comparaison entre l'heure planifiée et le premier
 * événement LOGIN réel du jour (uniquement pour les jours où une heure de début existe).
 */
@lombok.extern.slf4j.Slf4j
@Service
public class ScheduleService {

    private static final int LATE_THRESHOLD_MINUTES = 5;

    /** "Tue 01", "Wed 30"... — 3 lettres (jour de semaine abrégé, langue quelconque) + espace +
     *  1 ou 2 chiffres (jour du mois). Volontairement strict pour ne jamais confondre une
     *  colonne jour avec une colonne de synthèse ("A", "M2", "OFF"...) qui ne matche jamais ce motif. */
    private static final Pattern DAY_HEADER_PATTERN = Pattern.compile("^[A-Za-zÀ-ÿ]{3}\\s+(\\d{1,2})$");

    /** "Matin 08h-17h", "Nuit 21h-07h", "Après-midi 12h-21h"... — capture heure/minute de
     *  début et de fin. Les minutes sont optionnelles (ex. "8h" aussi bien que "08h30"). */
    private static final Pattern TIME_RANGE_PATTERN =
            Pattern.compile("(\\d{1,2})h(\\d{2})?\\s*-\\s*(\\d{1,2})h(\\d{2})?");

    private final AgentScheduleRepository agentScheduleRepository;
    private final ShiftEventRepository shiftEventRepository;
    private final UserRepository userRepository;
    private final UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository;
    private final ImportIntelligenceService importIntelligenceService;
    private final AuditLogService auditLogService;
    private final com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository;
    private final com.ecobank.rccportal.repository.RccNotificationRepository notificationRepository;

    public ScheduleService(AgentScheduleRepository agentScheduleRepository, ShiftEventRepository shiftEventRepository,
                           UserRepository userRepository, UserServiceAssignmentRepository userServiceAssignmentRepository,
                           com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository,
                           ImportIntelligenceService importIntelligenceService,
                           AuditLogService auditLogService,
                           com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository,
                           com.ecobank.rccportal.repository.RccNotificationRepository notificationRepository) {
        this.agentScheduleRepository = agentScheduleRepository;
        this.shiftEventRepository = shiftEventRepository;
        this.userRepository = userRepository;
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
        this.rccServiceRepository = rccServiceRepository;
        this.importIntelligenceService = importIntelligenceService;
        this.auditLogService = auditLogService;
        this.userRoleRepository = userRoleRepository;
        this.notificationRepository = notificationRepository;
    }

    private record ShiftDef(String label, LocalTime start, LocalTime end, boolean overnight) {}

    /** Les 4 shifts fixes proposés dans la modale "Planifier" (portail Excelliam) — indépendants
     *  de la légende dynamique d'un fichier importé puisqu'il n'y a ici aucun fichier, juste une
     *  saisie manuelle par le prestataire. Mêmes horaires que M/M2/A/N dans les fichiers RH réels
     *  (voir parseLegend) — cohérence volontaire pour que le même code affiche la même couleur/
     *  famille (planningChipClass côté shift.js) qu'il vienne d'un import ou de cette modale. */
    private static final Map<String, ShiftDef> FIXED_SHIFTS = Map.of(
            "M", new ShiftDef("Matin 07h-16h", LocalTime.of(7, 0), LocalTime.of(16, 0), false),
            "M2", new ShiftDef("Matin 08h-17h", LocalTime.of(8, 0), LocalTime.of(17, 0), false),
            "A", new ShiftDef("Après-midi 12h-21h", LocalTime.of(12, 0), LocalTime.of(21, 0), false),
            "N", new ShiftDef("Nuit 21h-06h", LocalTime.of(21, 0), LocalTime.of(6, 0), true)
    );

    @Transactional
    public ScheduleImportResult importFromExcel(MultipartFile file, String serviceCode, String countryCode,
                                                 String team, YearMonth month, String enteredByUsername) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Le fichier est requis.");
        }

        com.ecobank.rccportal.model.RccService importService = (serviceCode != null && !serviceCode.isBlank())
                ? rccServiceRepository.findByCodeIgnoreCase(serviceCode.trim())
                    .orElseThrow(() -> ApiException.badRequest("Unknown service: " + serviceCode))
                : null;
        String normalizedCountry = (countryCode != null && !countryCode.isBlank()) ? countryCode.trim().toUpperCase() : null;
        String normalizedTeam = (team != null && !team.isBlank()) ? team.trim().toUpperCase() : null;

        List<List<String>> grid = readGrid(file);

        int headerRowIndex = findDayHeaderRow(grid);
        try {
            if (headerRowIndex >= 0) {
                return importWideFormat(grid, headerRowIndex, month, importService, normalizedCountry, normalizedTeam,
                        file.getOriginalFilename(), enteredByUsername);
            }
            return importNarrowFormat(grid, importService, normalizedCountry, normalizedTeam,
                    file.getOriginalFilename(), enteredByUsername);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // Jamais une erreur "internal_error" opaque — toujours dire exactement quoi, pour
            // que la personne qui importe (ou l'IT) sache s'il faut corriger le fichier ou
            // nous remonter un vrai bug. Pile complète dans les logs serveur pour le diagnostic.
            log.error("[SCHEDULE IMPORT] Échec inattendu sur le fichier {}", file.getOriginalFilename(), e);
            throw ApiException.badRequest("Échec de l'import du planning (" + e.getClass().getSimpleName() + ") : " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lecture du fichier — Excel (.xlsx) ou CSV — vers une grille uniforme
    // ═══════════════════════════════════════════════════════════════════

    private List<List<String>> readGrid(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        try {
            if (filename.endsWith(".csv")) {
                return readCsvGrid(file);
            }
            return readXlsxGrid(file);
        } catch (IOException e) {
            throw ApiException.badRequest("Impossible de lire le fichier : " + e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier invalide ou mal formé : " + e.getMessage());
        }
    }

    /** Les exports CSV de ce type d'outil de planning sont très souvent en Windows-1252
     *  (accents français) plutôt qu'en UTF-8 — on essaie UTF-8 d'abord (le cas le plus sûr à
     *  détecter, via la présence d'un caractère de remplacement U+FFFD révélant un mauvais
     *  décodage), puis on retombe sur Windows-1252 qui couvre la quasi-totalité des exports
     *  Excel francophones réels. */
    private List<List<String>> readCsvGrid(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.indexOf('\uFFFD') >= 0) {
            content = new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }

        List<List<String>> grid = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new java.io.StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                grid.add(parseCsvLine(line));
            }
        }
        return grid;
    }

    /** Parseur CSV minimal mais correct sur les guillemets (une cellule "a, b" ne doit pas se
     *  couper au milieu) — suffisant ici, pas besoin d'une dépendance externe pour ça.
     *  IMPORTANT : un guillemet ne démarre un champ cité QUE s'il est le tout premier
     *  caractère de ce champ (RFC4180) — un guillemet ailleurs au milieu du texte (ex. le
     *  libellé légende brut Repos Maladie"RM") doit être gardé tel quel comme un caractère
     *  normal, jamais traité comme un début de citation, sous peine de désynchroniser tout le
     *  reste de la ligne (et parfois des lignes suivantes) derrière lui. */
    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean atFieldStart = true;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                    else inQuotes = false;
                } else current.append(c);
            } else {
                if (c == '"' && atFieldStart) {
                    inQuotes = true;
                    atFieldStart = false;
                } else if (c == ',') {
                    cells.add(current.toString());
                    current.setLength(0);
                    atFieldStart = true;
                } else {
                    current.append(c);
                    atFieldStart = false;
                }
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private List<List<String>> readXlsxGrid(MultipartFile file) throws IOException {
        List<List<String>> grid = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> line = new ArrayList<>();
                if (row != null) {
                    int lastCol = row.getLastCellNum();
                    for (int c = 0; c < lastCol; c++) {
                        line.add(cellToString(row.getCell(c)));
                    }
                }
                grid.add(line);
            }
        }
        return grid;
    }

    private String cellAt(List<List<String>> grid, int row, int col) {
        if (row < 0 || row >= grid.size()) return "";
        List<String> line = grid.get(row);
        if (col < 0 || col >= line.size()) return "";
        String v = line.get(col);
        return v == null ? "" : v.trim();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Format "grille mensuelle par équipe" — voir la javadoc de la classe
    // ═══════════════════════════════════════════════════════════════════

    private int findDayHeaderRow(List<List<String>> grid) {
        for (int r = 0; r < grid.size(); r++) {
            int matches = 0;
            for (String cell : grid.get(r)) {
                if (cell != null && DAY_HEADER_PATTERN.matcher(cell.trim()).matches()) matches++;
            }
            if (matches >= 5) return r; // une vraie ligne d'en-tête a bien plus que 5 jours — écarte les faux positifs isolés
        }
        return -1;
    }

    /** Repère les colonnes "Tue 01".."XXX NN" de la ligne d'en-tête → jour du mois. */
    private Map<Integer, Integer> findDayColumns(List<String> headerRow) {
        Map<Integer, Integer> colToDay = new LinkedHashMap<>();
        for (int c = 0; c < headerRow.size(); c++) {
            String cell = headerRow.get(c) == null ? "" : headerRow.get(c).trim();
            Matcher m = DAY_HEADER_PATTERN.matcher(cell);
            if (m.matches()) colToDay.put(c, Integer.parseInt(m.group(1)));
        }
        return colToDay;
    }

    /** Cherche "LÉGENDE" (accent-insensible) quelque part dans le fichier, puis lit les paires
     *  (code, libellé) qui suivent verticalement dans les 2 mêmes colonnes, jusqu'à une ligne
     *  vide ou "Notes de calcul" (fin de la légende dans ce type d'export). */
    private Map<String, ShiftDef> parseLegend(List<List<String>> grid) {
        Map<String, ShiftDef> legend = new LinkedHashMap<>();
        int legendRow = -1, legendCol = -1;
        outer:
        for (int r = 0; r < grid.size(); r++) {
            List<String> line = grid.get(r);
            for (int c = 0; c < line.size(); c++) {
                String cell = stripAccents(line.get(c) == null ? "" : line.get(c)).toUpperCase();
                if (cell.contains("LEGENDE")) { legendRow = r; legendCol = c; break outer; }
            }
        }
        if (legendRow < 0) return legend; // pas de légende trouvée — les codes resteront sans horaire, jamais bloquant

        for (int r = legendRow + 1; r < grid.size(); r++) {
            String code = cellAt(grid, r, legendCol);
            String label = cellAt(grid, r, legendCol + 1);
            if (code.isBlank() && label.isBlank()) continue; // ligne vide au milieu de la légende — on continue de scanner
            String codeUpper = stripAccents(code).toUpperCase();
            if (codeUpper.startsWith("NOTE") || codeUpper.startsWith("•")) break; // section "Notes de calcul" suivante — fin de la légende
            if (code.isBlank()) continue;

            Matcher m = TIME_RANGE_PATTERN.matcher(label);
            if (m.find()) {
                LocalTime start = LocalTime.of(Integer.parseInt(m.group(1)), m.group(2) != null ? Integer.parseInt(m.group(2)) : 0);
                LocalTime end = LocalTime.of(Integer.parseInt(m.group(3)), m.group(4) != null ? Integer.parseInt(m.group(4)) : 0);
                legend.put(codeUpper, new ShiftDef(label, start, end, end.isBefore(start) || end.equals(start)));
            } else {
                legend.put(codeUpper, new ShiftDef(label.isBlank() ? code : label, null, null, false));
            }
        }
        return legend;
    }

    private ScheduleImportResult importWideFormat(List<List<String>> grid, int headerRowIndex, YearMonth month,
                                                   com.ecobank.rccportal.model.RccService importService,
                                                   String normalizedCountry, String normalizedTeam,
                                                   String originalFilename, String enteredByUsername) {
        if (month == null) {
            throw ApiException.badRequest(
                    "Ce fichier est au format grille mensuelle (une colonne par jour) : choisissez le mois du planning avant d'importer.");
        }

        Map<Integer, Integer> dayColumns = findDayColumns(grid.get(headerRowIndex));
        Map<String, ShiftDef> legend = parseLegend(grid);

        // Vérifie que le jour 1 du fichier tombe bien sur le jour de semaine attendu pour le
        // mois choisi — plutôt qu'importer silencieusement un planning décalé d'un mois.
        Integer firstDayCol = dayColumns.entrySet().stream()
                .filter(e -> e.getValue() == 1).map(Map.Entry::getKey).findFirst().orElse(null);
        if (firstDayCol != null) {
            String firstDayLabel = cellAt(grid, headerRowIndex, firstDayCol);
            String expectedAbbrev = stripAccents(month.atDay(1).getDayOfWeek()
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).toUpperCase();
            String actualAbbrev = stripAccents(firstDayLabel.replaceAll("\\s*\\d+$", "")).toUpperCase();
            if (!actualAbbrev.isBlank() && !actualAbbrev.startsWith(expectedAbbrev.substring(0, Math.min(3, expectedAbbrev.length())))) {
                throw ApiException.badRequest("Le mois choisi (" + month + ") ne correspond pas au fichier : le 1er y tombe un « "
                        + firstDayLabel + " », mais le 1er " + month + " est un " +
                        month.atDay(1).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRENCH) + ". Vérifiez le mois sélectionné.");
            }
        }

        Map<String, User> usersByNormalizedName = new HashMap<>();
        for (User u : userRepository.findAll()) {
            if (u.getName() != null && !u.getName().isBlank()) {
                usersByNormalizedName.putIfAbsent(normalizeName(u.getName()), u);
            }
        }

        int rowsProcessed = 0, entriesCreated = 0, usersAutoCreated = 0;
        List<String> unresolvedNames = new ArrayList<>();
        List<String> anomalies = new ArrayList<>();
        List<com.ecobank.rccportal.dto.ImportedSchedulePreview> preview = new ArrayList<>();
        List<String> skippedRows = new ArrayList<>();
        final int previewCap = 80;
        Set<String> unmappedCodes = new TreeSet<>();
        Set<Long> importedUserIds = new HashSet<>();

        for (int r = headerRowIndex + 1; r < grid.size(); r++) {
            String rowNumCell = cellAt(grid, r, 0);
            String name = cellAt(grid, r, 1);
            boolean looksLikeAgentRow = !rowNumCell.isBlank() && rowNumCell.matches("\\d+") && !name.isBlank();
            if (!looksLikeAgentRow) {
                // Ligne d'équipe ("Team Inbound Voix"), ligne TOTAL, ou ligne vide — jamais une
                // erreur : on saute et on continue, la fin du fichier n'est pas toujours nette.
                continue;
            }
            rowsProcessed++;

            User user = usersByNormalizedName.get(normalizeName(name));
            boolean isNew = false;
            if (user == null) {
                if (importIntelligenceService.isAvailable() && !usersByNormalizedName.isEmpty()) {
                    List<String> candidates = usersByNormalizedName.values().stream()
                            .map(User::getName).filter(n -> n != null && !n.isBlank()).distinct().toList();
                    Optional<String> matched = importIntelligenceService.resolveAmbiguousName(name, candidates);
                    if (matched.isPresent()) user = usersByNormalizedName.get(normalizeName(matched.get()));
                }
            }
            if (user == null) {
                String username = generateUsername(name);
                user = User.builder()
                        .username(username)
                        .name(name.trim().length() > 200 ? name.trim().substring(0, 200) : name.trim())
                        .status("APPROVED").accountEnabled(true).accountLocked(false)
                        .accountExpired(false).credentialsExpired(false).failedAttempts(0)
                        .affiliateBranch(normalizedCountry != null && normalizedCountry.length() > 3
                                ? normalizedCountry.substring(0, 3) : normalizedCountry)
                        .activity(normalizedTeam)
                        .build();
                user = userRepository.save(user);
                usersByNormalizedName.put(normalizeName(name), user);
                usersAutoCreated++;
                isNew = true;
            }
            if (!isNew) linkTeamIfMissing(user, importService, normalizedCountry, normalizedTeam);
            else linkServiceIfMissing(user, importService);
            importedUserIds.add(user.getId());

            for (Map.Entry<Integer, Integer> dayCol : dayColumns.entrySet()) {
                String rawCode = cellAt(grid, r, dayCol.getKey());
                if (rawCode.isBlank()) continue;
                String code = rawCode.toUpperCase();

                LocalDate workDate;
                try {
                    workDate = month.atDay(dayCol.getValue());
                } catch (Exception e) {
                    continue; // jour hors plage du mois (ex. 31 sur un mois à 30 jours) — cellule ignorée, pas une erreur
                }

                ShiftDef def = legend.get(code);
                if (def == null) unmappedCodes.add(code);

                final User scheduleUser = user;
                AgentSchedule schedule = agentScheduleRepository.findByUserAndWorkDate(user, workDate)
                        .orElseGet(() -> AgentSchedule.builder().user(scheduleUser).workDate(workDate).build());
                schedule.setShiftCode(code);
                schedule.setShiftLabel(def != null ? def.label() : code);
                schedule.setPlannedStartTime(def != null ? def.start() : null);
                schedule.setPlannedEndTime(def != null ? def.end() : null);
                schedule.setOvernightCrossesMidnight(def != null && def.overnight());
                agentScheduleRepository.save(schedule);
                entriesCreated++;

                if (preview.size() < previewCap) {
                    preview.add(new com.ecobank.rccportal.dto.ImportedSchedulePreview(
                            user.getName(), workDate.toString(),
                            def != null && def.start() != null ? def.start().toString() : code,
                            def != null ? def.label() : code));
                }
            }
        }

        if (!unmappedCodes.isEmpty()) {
            anomalies.add("Code(s) sans correspondance dans la légende du fichier (importés tels quels, sans horaire) : "
                    + String.join(", ", unmappedCodes) + ".");
        }

        // Analyse de complétude — vérifie qu'aucun agent déjà connu de cette équipe n'a été
        // oublié par le fichier (ex. un agent absent du fichier ce mois-ci alors qu'il est
        // toujours dans l'équipe) : jamais silencieux, toujours remonté comme anomalie à vérifier.
        if (normalizedTeam != null) {
            List<String> missingFromFile = userRepository.findAll().stream()
                    .filter(u -> normalizedTeam.equalsIgnoreCase(u.getActivity()))
                    .filter(u -> !importedUserIds.contains(u.getId()))
                    .map(User::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .sorted()
                    .toList();
            if (!missingFromFile.isEmpty()) {
                anomalies.add("Agent(s) déjà rattaché(s) à cette équipe mais ABSENT(S) de ce fichier (à vérifier — départ, oubli, ou nom orthographié différemment) : "
                        + String.join(", ", missingFromFile) + ".");
            }
        }

        auditLogService.record(enteredByUsername, "IMPORT_SCHEDULE_WIDE",
                "Fichier : " + (originalFilename != null ? originalFilename : "(sans nom)") + " — mois " + month + " — "
                        + rowsProcessed + " agent(s), " + entriesCreated + " case(s) capturée(s), "
                        + usersAutoCreated + " compte(s) créé(s)"
                        + (unmappedCodes.isEmpty() ? "" : ", codes non mappés : " + unmappedCodes));

        return new ScheduleImportResult(rowsProcessed, entriesCreated, usersAutoCreated, unresolvedNames,
                importIntelligenceService.summarizeAnomalies(anomalies, null),
                preview, entriesCreated > preview.size(), skippedRows);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Ancien format — matricule, date, heure de début (repli si aucune ligne
    // d'en-tête "jour du mois" n'a été détectée dans le fichier)
    // ═══════════════════════════════════════════════════════════════════

    private ScheduleImportResult importNarrowFormat(List<List<String>> grid,
                                                      com.ecobank.rccportal.model.RccService importService,
                                                      String normalizedCountry, String normalizedTeam,
                                                      String originalFilename, String enteredByUsername) {
        int rowsProcessed = 0, entriesCreated = 0, usersAutoCreated = 0;
        List<String> unknownMatricules = new ArrayList<>();
        List<String> scheduleAnomalies = new ArrayList<>();
        List<com.ecobank.rccportal.dto.ImportedSchedulePreview> preview = new ArrayList<>();
        List<String> skippedRows = new ArrayList<>();
        final int previewCap = 80;

        for (int rowIndex = 1; rowIndex < grid.size(); rowIndex++) {
            List<String> row = grid.get(rowIndex);
            String matricule = row.size() > 0 && row.get(0) != null ? row.get(0).trim() : "";
            if (matricule.isBlank()) continue;
            rowsProcessed++;

            User user = userRepository.findFirstByUsernameIgnoreCase(matricule).orElse(null);
            if (user == null) {
                user = User.builder()
                        .username(matricule).name(matricule).status("APPROVED")
                        .accountEnabled(true).accountLocked(false).accountExpired(false)
                        .credentialsExpired(false).failedAttempts(0)
                        .affiliateBranch(normalizedCountry).activity(normalizedTeam)
                        .build();
                user = userRepository.save(user);
                linkServiceIfMissing(user, importService);
                usersAutoCreated++;
            } else {
                linkTeamIfMissing(user, importService, normalizedCountry, normalizedTeam);
            }

            LocalDate workDate = parseDate(row.size() > 1 ? row.get(1) : null);
            LocalTime startTime = parseTime(row.size() > 2 ? row.get(2) : null);
            if (workDate == null || startTime == null) {
                String reason = workDate == null && startTime == null ? "date ET heure illisibles"
                        : workDate == null ? "date illisible (colonne B)" : "heure illisible (colonne C)";
                skippedRows.add(matricule + " (ligne " + (rowIndex + 1) + ") : " + reason + ".");
                continue;
            }

            if (startTime.isBefore(LocalTime.of(4, 0)) || startTime.isAfter(LocalTime.of(23, 30))) {
                scheduleAnomalies.add(matricule + " — prise de poste à " + startTime + " le " + workDate
                        + " : horaire inhabituel, à vérifier.");
            }

            final User scheduleUser = user;
            AgentSchedule schedule = agentScheduleRepository.findByUserAndWorkDate(user, workDate)
                    .orElseGet(() -> AgentSchedule.builder().user(scheduleUser).workDate(workDate).build());
            schedule.setPlannedStartTime(startTime);
            schedule.setShiftCode(null);
            schedule.setShiftLabel(null);
            agentScheduleRepository.save(schedule);
            entriesCreated++;
            if (preview.size() < previewCap) {
                preview.add(new com.ecobank.rccportal.dto.ImportedSchedulePreview(
                        user.getName(), workDate.toString(), startTime.toString(), null));
            }
        }

        auditLogService.record(enteredByUsername, "IMPORT_EXCEL_SCHEDULE",
                "Fichier : " + (originalFilename != null ? originalFilename : "(sans nom)") +
                " — " + rowsProcessed + " ligne(s) traitée(s), " + entriesCreated + " capturée(s), " +
                usersAutoCreated + " compte(s) créé(s), " + skippedRows.size() + " ligne(s) non capturée(s)");

        return new ScheduleImportResult(rowsProcessed, entriesCreated, usersAutoCreated, unknownMatricules,
                importIntelligenceService.summarizeAnomalies(scheduleAnomalies, null),
                preview, entriesCreated > preview.size(), skippedRows);
    }

    private void linkTeamIfMissing(User user, com.ecobank.rccportal.model.RccService importService, String countryCode, String team) {
        boolean changed = false;
        if ((user.getAffiliateBranch() == null || user.getAffiliateBranch().isBlank()) && countryCode != null) {
            user.setAffiliateBranch(countryCode);
            changed = true;
        }
        if ((user.getActivity() == null || user.getActivity().isBlank()) && team != null) {
            user.setActivity(team);
            changed = true;
        }
        if (changed) userRepository.save(user);
        linkServiceIfMissing(user, importService);
    }

    private void linkServiceIfMissing(User user, com.ecobank.rccportal.model.RccService importService) {
        if (importService == null || user.getId() == null) return;
        if (!userServiceAssignmentRepository.existsByUserIdAndServiceId(user.getId(), importService.getId())) {
            userServiceAssignmentRepository.save(UserServiceAssignment.builder().user(user).service(importService).build());
        }
    }

    private String normalizeName(String name) {
        String cleaned = stripAccents(name.trim().toLowerCase()).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        // Trie les mots pour tolérer "Prénom Nom" vs "Nom Prénom" — même logique que RosterImportService.
        String[] words = cleaned.split(" ");
        Arrays.sort(words);
        return String.join(" ", words);
    }

    /** USERNAME fait 35 caractères en base (voir 01_schema.sql) — on plafonne à 30 pour garder
     *  de la place à un éventuel suffixe de collision. Même principe qu'AttendanceImportService
     *  et ManualKpiEntryService : les noms ouest-africains composés dépassent régulièrement 35
     *  caractères une fois convertis en "prenom.nom.autre.nom" (ex. "Kone Nangbama Kinaya Cecilia
     *  Premium Vendredi et Samedi"), et une troncature naïve à l'insert (au lieu d'ici, en amont)
     *  fait échouer TOUT l'import SQL Server en DataIntegrityViolationException plutôt qu'une
     *  seule ligne. */
    /** USERNAME fait 35 caractères en base (voir 01_schema.sql) — on plafonne à 30 pour garder
     *  de la place à un éventuel suffixe de collision. Même principe qu'AttendanceImportService
     *  et ManualKpiEntryService : les noms ouest-africains composés dépassent régulièrement 35
     *  caractères une fois convertis en "prenom.nom.autre.nom" (ex. "Kone Nangbama Kinaya Cecilia
     *  Premium Vendredi et Samedi"), et une troncature naïve à l'insert (au lieu d'ici, en amont)
     *  fait échouer TOUT l'import SQL Server en DataIntegrityViolationException plutôt qu'une
     *  seule ligne.
     *  \\p{Z} (avant le nettoyage) normalise aussi les espaces Unicode non standards (ex. \u00A0
     *  espace insécable, fréquent dans les exports Excel/CSV copiés-collés) vers un espace normal —
     *  le \\s Java natif ne les reconnaît PAS, ce qui sinon recolle deux mots sans séparateur
     *  (ex. "ceciliapremium" au lieu de "cecilia.premium") sans faire échouer l'import pour autant. */
    private String generateUsername(String fullName) {
        String base = stripAccents(fullName.trim().toLowerCase())
                .replaceAll("\\p{Z}", " ")
                .replaceAll("[^a-z0-9\\s]", "")
                .trim()
                .replaceAll("\\s+", ".");
        if (base.isBlank()) base = "agent";
        if (base.length() > 30) base = base.substring(0, 30);
        base = base.replaceAll("\\.$", "");

        String candidate = base;
        int suffix = 2;
        while (userRepository.existsByUsernameIgnoreCase(candidate)) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }

    private String stripAccents(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /**
     * Retards du jour — un agent est en retard si son premier LOGIN du jour dépasse
     * l'heure planifiée de plus de LATE_THRESHOLD_MINUTES. Ne renvoie que les agents
     * ayant un planning ce jour-là AVEC une heure de début (les jours OFF/ABS/Congés/
     * etc. n'ont pas de notion de retard — voir AgentSchedule.plannedStartTime, nullable).
     */
    @Transactional(readOnly = true)
    public List<LatenessResponse> latenessForDate(LocalDate date) {
        List<AgentSchedule> schedules = agentScheduleRepository.findByWorkDate(date).stream()
                // Un planning encore en attente de validation ne peut pas rendre un agent « en retard ».
                .filter(s -> "APPROVED".equals(s.getApprovalStatus()))
                .filter(s -> s.getPlannedStartTime() != null)
                .toList();
        if (schedules.isEmpty()) return List.of();

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<ShiftEvent> dayEvents = shiftEventRepository
                .findByOccurredAtBetweenOrderByUser_UsernameAscOccurredAtAsc(dayStart, dayEnd);

        Map<Long, LocalTime> firstLoginByUserId = new HashMap<>();
        for (ShiftEvent e : dayEvents) {
            if (!"LOGIN".equals(e.getEventType())) continue;
            Long userId = e.getUser().getId();
            firstLoginByUserId.putIfAbsent(userId, e.getOccurredAt().toLocalTime());
        }

        List<LatenessResponse> results = new ArrayList<>();
        for (AgentSchedule schedule : schedules) {
            User user = schedule.getUser();
            LocalTime actualLogin = firstLoginByUserId.get(user.getId());
            Integer lateMinutes = null;
            if (actualLogin != null) {
                long minutes = Duration.between(schedule.getPlannedStartTime(), actualLogin).toMinutes();
                if (minutes > LATE_THRESHOLD_MINUTES) {
                    lateMinutes = (int) minutes;
                }
            }
            results.add(new LatenessResponse(user.getUsername(), user.getName(), getPrimaryService(user),
                    schedule.getShiftCode(), schedule.getShiftLabel(),
                    schedule.getPlannedStartTime(), actualLogin, lateMinutes));
        }

        results.sort((a, b) -> {
            if (a.lateMinutes() == null && b.lateMinutes() == null) return 0;
            if (a.lateMinutes() == null) return 1;
            if (b.lateMinutes() == null) return -1;
            return b.lateMinutes() - a.lateMinutes();
        });
        return results;
    }

    /**
     * Planification directe par shift (modale "Planifier" du portail Excelliam) — sans fichier,
     * le prestataire coche des agents d'une équipe et choisit un shift par agent pour toute la
     * période (periodFrom -> periodTo, un même shift répété chaque jour de la période, pas de
     * grille jour par jour comme l'import fichier). Écrit dans AgentSchedule, la même table que
     * l'import RH — donc immédiatement visible partout où le planning est déjà affiché (RH, Admin,
     * Superviseur, Team Leader, Agent) sans synchronisation supplémentaire à construire.
     *
     * Écrit avec approvalStatus="PENDING" (jamais APPROVED directement) — le Team Leader de
     * l'équipe DOIT valider avant que les agents ne voient leur planning (voir
     * planningForUser/planningForTeam, qui filtrent sur APPROVED). Toute nouvelle planification
     * remet le statut à PENDING même si une version précédente avait déjà été validée : un
     * planning modifié redevient à valider, jamais silencieusement réputé toujours bon.
     *
     * "OFF" retire l'agent du planning de cette période (jour de repos, aucun horaire) plutôt que
     * de refuser la requête — permet de corriger une planification en cochant à nouveau l'agent
     * avec OFF, sans devoir supprimer manuellement en base.
     */
    @Transactional
    public com.ecobank.rccportal.dto.PlanifyShiftsResult planifyShifts(com.ecobank.rccportal.dto.PlanifyShiftsRequest request) {
        if (request == null || request.assignments() == null || request.assignments().isEmpty()) {
            throw ApiException.badRequest("Au moins un agent doit être coché.");
        }
        if (request.periodFrom() == null || request.periodTo() == null) {
            throw ApiException.badRequest("La période (du/au) est requise.");
        }
        if (request.periodFrom().isAfter(request.periodTo())) {
            throw ApiException.badRequest("La date de début doit précéder la date de fin.");
        }

        int agentsPlanified = 0, entriesCreated = 0;
        List<String> unknownUsernames = new ArrayList<>();
        java.util.Set<String> teamsToNotify = new java.util.HashSet<>();

        for (com.ecobank.rccportal.dto.PlanifyShiftsRequest.AgentShiftAssignment a : request.assignments()) {
            if (a.username() == null || a.username().isBlank()) continue;
            String code = a.shiftCode() == null ? "" : a.shiftCode().trim().toUpperCase();
            boolean isOff = "OFF".equals(code);
            ShiftDef def = FIXED_SHIFTS.get(code);
            if (!isOff && def == null) {
                throw ApiException.badRequest("Shift inconnu : \"" + code + "\" — attendu : "
                        + String.join(", ", FIXED_SHIFTS.keySet()) + " ou OFF.");
            }

            User user = userRepository.findFirstByUsernameIgnoreCase(a.username().trim()).orElse(null);
            if (user == null) {
                unknownUsernames.add(a.username());
                continue;
            }
            agentsPlanified++;
            if (user.getActivity() != null && !user.getActivity().isBlank()) teamsToNotify.add(user.getActivity());

            for (LocalDate d = request.periodFrom(); !d.isAfter(request.periodTo()); d = d.plusDays(1)) {
                final User scheduleUser = user;
                final LocalDate day = d;
                AgentSchedule schedule = agentScheduleRepository.findByUserAndWorkDate(user, day)
                        .orElseGet(() -> AgentSchedule.builder().user(scheduleUser).workDate(day).build());
                schedule.setShiftCode(isOff ? "OFF" : code);
                schedule.setShiftLabel(isOff ? "Repos hebdomadaire" : def.label());
                schedule.setPlannedStartTime(isOff ? null : def.start());
                schedule.setPlannedEndTime(isOff ? null : def.end());
                schedule.setOvernightCrossesMidnight(!isOff && def.overnight());
                schedule.setApprovalStatus("PENDING");
                schedule.setRejectionReason(null);
                schedule.setOrigin("EXCELLIAM");
                agentScheduleRepository.save(schedule);
                entriesCreated++;
            }
        }

        for (String team : teamsToNotify) {
            User teamLeader = findTeamLeaderForTeam(team);
            if (teamLeader != null) {
                notificationRepository.save(com.ecobank.rccportal.model.RccNotification.builder()
                        .targetUser(teamLeader)
                        .content("Nouveau planning à valider pour votre équipe (" + team + ") — voir Suivi de shift.")
                        .isRead(false)
                        .build());
            }
        }

        return new com.ecobank.rccportal.dto.PlanifyShiftsResult(agentsPlanified, entriesCreated, unknownUsernames);
    }

    /**
     * Nouveau flux symétrique — le Team Leader planifie lui-même sa propre équipe (même
     * principe que planifyShifts côté Excelliam : un shift par agent sur une période) et
     * l'envoie à Excelliam pour validation. Toujours restreint à SA PROPRE équipe (User.ledTeam),
     * vérifié ici côté serveur — jamais confié au frontend, même si un agent listé dans la
     * requête n'appartient pas à son équipe, il est rejeté explicitement (pas juste ignoré) pour
     * qu'un mauvais mapping ne passe pas inaperçu.
     */
    @Transactional
    public com.ecobank.rccportal.dto.PlanifyShiftsResult submitTeamPlanning(
            com.ecobank.rccportal.security.AuthenticatedUser requester, com.ecobank.rccportal.dto.PlanifyShiftsRequest request) {
        if (!"team_leader".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Seul un Team Leader peut soumettre un planning à Excelliam.");
        }
        User teamLeader = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        String ownTeam = teamLeader.getLedTeam();
        if (ownTeam == null || ownTeam.isBlank()) {
            throw ApiException.forbidden("Vous ne dirigez aucune équipe.");
        }
        if (request == null || request.assignments() == null || request.assignments().isEmpty()) {
            throw ApiException.badRequest("Au moins un agent doit être coché.");
        }
        if (request.periodFrom() == null || request.periodTo() == null) {
            throw ApiException.badRequest("La période (du/au) est requise.");
        }
        if (request.periodFrom().isAfter(request.periodTo())) {
            throw ApiException.badRequest("La date de début doit précéder la date de fin.");
        }

        int agentsPlanified = 0, entriesCreated = 0;
        List<String> unknownUsernames = new ArrayList<>();

        for (com.ecobank.rccportal.dto.PlanifyShiftsRequest.AgentShiftAssignment a : request.assignments()) {
            if (a.username() == null || a.username().isBlank()) continue;
            String code = a.shiftCode() == null ? "" : a.shiftCode().trim().toUpperCase();
            boolean isOff = "OFF".equals(code);
            ShiftDef def = FIXED_SHIFTS.get(code);
            if (!isOff && def == null) {
                throw ApiException.badRequest("Shift inconnu : \"" + code + "\" — attendu : "
                        + String.join(", ", FIXED_SHIFTS.keySet()) + " ou OFF.");
            }

            User user = userRepository.findFirstByUsernameIgnoreCase(a.username().trim()).orElse(null);
            if (user == null) {
                unknownUsernames.add(a.username());
                continue;
            }
            if (user.getActivity() == null || !ownTeam.equalsIgnoreCase(user.getActivity())) {
                throw ApiException.forbidden("L'agent \"" + a.username() + "\" n'appartient pas à votre équipe (" + ownTeam + ").");
            }
            agentsPlanified++;

            for (LocalDate d = request.periodFrom(); !d.isAfter(request.periodTo()); d = d.plusDays(1)) {
                final User scheduleUser = user;
                final LocalDate day = d;
                AgentSchedule schedule = agentScheduleRepository.findByUserAndWorkDate(user, day)
                        .orElseGet(() -> AgentSchedule.builder().user(scheduleUser).workDate(day).build());
                schedule.setShiftCode(isOff ? "OFF" : code);
                schedule.setShiftLabel(isOff ? "Repos hebdomadaire" : def.label());
                schedule.setPlannedStartTime(isOff ? null : def.start());
                schedule.setPlannedEndTime(isOff ? null : def.end());
                schedule.setOvernightCrossesMidnight(!isOff && def.overnight());
                schedule.setApprovalStatus("PENDING");
                schedule.setRejectionReason(null);
                schedule.setOrigin("TEAM_LEADER");
                agentScheduleRepository.save(schedule);
                entriesCreated++;
            }
        }

        String content = "Le Team Leader de l'équipe " + ownTeam + " a soumis un nouveau planning à valider ("
                + entriesCreated + " entrée(s), du " + request.periodFrom() + " au " + request.periodTo() + ").";
        userRoleRepository.findByRoleNameIgnoreCase("EXCELLIAM").stream()
                .map(com.ecobank.rccportal.model.UserRole::getUser)
                .distinct()
                .forEach(u -> notificationRepository.save(com.ecobank.rccportal.model.RccNotification.builder()
                        .targetUser(u).content(content).isRead(false).build()));

        return new com.ecobank.rccportal.dto.PlanifyShiftsResult(agentsPlanified, entriesCreated, unknownUsernames);
    }

    /**
     * Excelliam valide ou refuse le planning soumis par un Team Leader (origin = TEAM_LEADER,
     * statut PENDING). Une validation ne rend PAS le planning visible immédiatement : elle
     * passe seulement au statut "VALIDATED", en attente du clic "Mise à jour" du Team Leader
     * (voir publishTeamPlanning) — c'est la mise à jour globale demandée explicitement par le
     * métier, pour que le Team Leader garde la main sur le moment où ça devient effectif.
     * Un refus, lui, est immédiat (REJECTED, motif obligatoire) — le Team Leader n'a qu'à
     * corriger et resoumettre.
     */
    @Transactional
    public int decideExcelliamValidation(com.ecobank.rccportal.security.AuthenticatedUser requester,
                                          String team, LocalDate from, LocalDate to, boolean approve, String reason) {
        if (!"excelliam".equalsIgnoreCase(requester.role()) && !"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Seul Excelliam (ou un administrateur) peut valider un planning soumis par un Team Leader.");
        }
        if (team == null || team.isBlank()) {
            throw ApiException.badRequest("L'équipe est requise.");
        }
        if (!approve && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("Un motif est requis pour refuser un planning.");
        }

        List<AgentSchedule> pending = agentScheduleRepository.findByWorkDateBetween(from, to).stream()
                .filter(s -> "PENDING".equals(s.getApprovalStatus()))
                .filter(s -> "TEAM_LEADER".equals(s.getOrigin()))
                .filter(s -> team.equalsIgnoreCase(s.getUser().getActivity()))
                .toList();
        if (pending.isEmpty()) {
            throw ApiException.badRequest("Aucun planning soumis par le Team Leader en attente pour cette équipe sur cette période.");
        }

        String newStatus = approve ? "VALIDATED" : "REJECTED";
        for (AgentSchedule s : pending) {
            s.setApprovalStatus(newStatus);
            s.setRejectionReason(approve ? null : reason.trim());
            agentScheduleRepository.save(s);
        }

        String content = approve
                ? "Excelliam a validé le planning soumis (" + pending.size() + " entrée(s)) — cliquez \"Mise à jour\" pour l'appliquer."
                : "Excelliam a REFUSÉ le planning soumis — motif : " + reason.trim();
        User teamLeader = findTeamLeaderForTeam(team);
        if (teamLeader != null) {
            notificationRepository.save(com.ecobank.rccportal.model.RccNotification.builder()
                    .targetUser(teamLeader).content(content).isRead(false).build());
        }
        return pending.size();
    }

    /**
     * Clic "Mise à jour" du Team Leader — dernière étape du flux TEAM_LEADER : bascule en
     * APPROVED (donc effectivement en ligne, visible partout : planning agent, reporting,
     * retards) toutes les entrées VALIDATED de sa propre équipe sur la période donnée. Tant que
     * ce clic n'a pas eu lieu, même après validation Excelliam, rien ne change pour les agents.
     */
    @Transactional
    public int publishTeamPlanning(com.ecobank.rccportal.security.AuthenticatedUser requester, LocalDate from, LocalDate to) {
        if (!"team_leader".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Seul un Team Leader peut déclencher la mise à jour de son planning.");
        }
        User teamLeader = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        String ownTeam = teamLeader.getLedTeam();
        if (ownTeam == null || ownTeam.isBlank()) {
            throw ApiException.forbidden("Vous ne dirigez aucune équipe.");
        }

        List<AgentSchedule> validated = agentScheduleRepository.findByWorkDateBetween(from, to).stream()
                .filter(s -> "VALIDATED".equals(s.getApprovalStatus()))
                .filter(s -> "TEAM_LEADER".equals(s.getOrigin()))
                .filter(s -> ownTeam.equalsIgnoreCase(s.getUser().getActivity()))
                .toList();
        if (validated.isEmpty()) {
            throw ApiException.badRequest("Aucun planning validé par Excelliam en attente de mise à jour pour votre équipe sur cette période.");
        }
        for (AgentSchedule s : validated) {
            s.setApprovalStatus("APPROVED");
            agentScheduleRepository.save(s);
        }
        return validated.size();
    }

    /** Team Leader dont User.ledTeam correspond à l'équipe donnée — même logique que
     *  WorkflowService.findTeamLeaderForTeam, dupliquée ici volontairement : ScheduleService
     *  ne dépend pas de WorkflowService (couches distinctes), et cette résolution est petite. */
    private User findTeamLeaderForTeam(String team) {
        return userRoleRepository.findByRoleNameIgnoreCase("TEAM_LEADER").stream()
                .map(com.ecobank.rccportal.model.UserRole::getUser)
                .filter(u -> u.getLedTeam() != null && u.getLedTeam().equalsIgnoreCase(team))
                .findFirst()
                .orElse(null);
    }

    /**
     * Le Team Leader de l'équipe valide ou refuse le planning en attente (PENDING) sur une
     * période — TOUTES les entrées PENDING de son équipe sur cette période, pas agent par
     * agent (le planning se valide en bloc, cohérent avec la demande métier : "si ça leur
     * convient ils valident, sinon ils refusent avec un motif").
     * approve=false EXIGE un motif (reason) — jamais un refus silencieux, Excelliam doit
     * savoir quoi corriger.
     */
    @Transactional
    public int decideMonthlyPlanning(String teamLeaderUsername, LocalDate from, LocalDate to, boolean approve, String reason) {
        User teamLeader = userRepository.findFirstByUsernameIgnoreCase(teamLeaderUsername)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));
        String team = teamLeader.getLedTeam();
        if (team == null || team.isBlank()) {
            throw ApiException.forbidden("Vous n'êtes Team Leader d'aucune équipe.");
        }
        if (!approve && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("Un motif est requis pour refuser un planning.");
        }

        List<AgentSchedule> pending = agentScheduleRepository.findByWorkDateBetween(from, to).stream()
                .filter(s -> "PENDING".equals(s.getApprovalStatus()))
                .filter(s -> "EXCELLIAM".equals(s.getOrigin()))
                .filter(s -> team.equalsIgnoreCase(s.getUser().getActivity()))
                .toList();
        if (pending.isEmpty()) {
            throw ApiException.badRequest("Aucun planning en attente de validation pour votre équipe sur cette période.");
        }

        String newStatus = approve ? "APPROVED" : "REJECTED";
        for (AgentSchedule s : pending) {
            s.setApprovalStatus(newStatus);
            s.setRejectionReason(approve ? null : reason.trim());
            agentScheduleRepository.save(s);
        }

        // Notifie Excelliam du résultat — surtout utile en cas de refus, pour qu'il sache
        // qu'il doit ajuster le planning (le motif est déjà visible dans son dashboard).
        String content = approve
                ? "Planning de l'équipe " + team + " validé par le Team Leader (" + pending.size() + " entrée(s))."
                : "Planning de l'équipe " + team + " REFUSÉ par le Team Leader — motif : " + reason.trim();
        userRoleRepository.findByRoleNameIgnoreCase("EXCELLIAM").stream()
                .map(com.ecobank.rccportal.model.UserRole::getUser)
                .distinct()
                .forEach(u -> notificationRepository.save(com.ecobank.rccportal.model.RccNotification.builder()
                        .targetUser(u).content(content).isRead(false).build()));

        return pending.size();
    }

    /**
     * Planning d'un agent sur une plage de dates — jour/semaine/mois côté appelant (voir
     * ScheduleController et shift.js/team-leader.js) : un seul point d'entrée, la granularité
     * n'est qu'un choix de "from"/"to" côté client, jamais une notion différente côté serveur.
     * N'inclut JAMAIS une entrée en attente (PENDING) ou refusée (REJECTED) — un agent ne voit
     * son planning Excelliam qu'une fois validé par son Team Leader (voir planifyShifts /
     * decideMonthlyPlanning). Un import RH classique reste APPROVED d'emblée, donc toujours visible.
     */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.AgentScheduleResponse> planningForUser(String username, LocalDate from, LocalDate to) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));
        return agentScheduleRepository.findByUserAndWorkDateBetweenOrderByWorkDateAsc(user, from, to).stream()
                .filter(s -> "APPROVED".equals(s.getApprovalStatus()))
                .map(this::toResponse)
                .toList();
    }

    /** Même principe, pour toute une équipe (activity) — portail Team Leader/Superviseur/RH.
     *  includePending=true montre aussi PENDING/REJECTED (Team Leader validant son équipe,
     *  Excelliam/Admin suivant l'état de ses planifications) — false pour tout le reste
     *  (un Agent ne doit jamais voir un planning pas encore validé, même via /team). */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.AgentScheduleResponse> planningForTeam(String team, LocalDate from, LocalDate to, boolean includePending) {
        List<AgentSchedule> schedules = (team == null || team.isBlank())
                ? agentScheduleRepository.findByWorkDateBetweenOrderByWorkDateAsc(from, to)
                : agentScheduleRepository.findByWorkDateBetweenOrderByWorkDateAsc(from, to).stream()
                    .filter(s -> team.equalsIgnoreCase(s.getUser().getActivity()))
                    .toList();
        if (!includePending) {
            schedules = schedules.stream().filter(s -> "APPROVED".equals(s.getApprovalStatus())).toList();
        }
        return schedules.stream().map(this::toResponse).toList();
    }

    /**
     * Même chose, mais avec le contrôle d'accès par équipe attendu : QA/RH/Superviseur/Admin/
     * Excelliam voient toutes les équipes (y compris "Toutes" = team vide), PENDING/REJECTED
     * compris (ils suivent l'état de la validation). Un Team Leader voit SA propre équipe,
     * PENDING/REJECTED compris (c'est lui qui valide). Un Agent ne voit que SA propre équipe,
     * et JAMAIS PENDING/REJECTED — la restriction est imposée ici côté serveur, jamais confiée
     * au seul frontend.
     */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.AgentScheduleResponse> planningForTeamScoped(
            com.ecobank.rccportal.security.AuthenticatedUser requester, String requestedTeam, LocalDate from, LocalDate to) {
        boolean broadAccess = "admin".equalsIgnoreCase(requester.role())
                || "rh".equalsIgnoreCase(requester.role())
                || "excelliam".equalsIgnoreCase(requester.role())
                || "supervisor".equalsIgnoreCase(requester.role())
                || (requester.service() != null
                    && Set.of("quality assurance", "superviseur qa").contains(requester.service().toLowerCase().replace('_', ' ')));

        if (broadAccess) {
            return planningForTeam(requestedTeam, from, to, true);
        }

        boolean isTeamLeader = "team_leader".equalsIgnoreCase(requester.role());

        // Team Leader / Agent : toujours SA propre équipe, jamais celle demandée par le client.
        User self = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        String ownTeam = self.getActivity();
        if (ownTeam == null || ownTeam.isBlank()) return List.of(); // "Non classée" — aucune équipe à montrer
        return planningForTeam(ownTeam, from, to, isTeamLeader);
    }

    private com.ecobank.rccportal.dto.AgentScheduleResponse toResponse(AgentSchedule s) {
        return new com.ecobank.rccportal.dto.AgentScheduleResponse(
                s.getUser().getUsername(), s.getUser().getName(), s.getUser().getActivity(),
                s.getWorkDate(), s.getPlannedStartTime(), s.getPlannedEndTime(),
                s.getShiftCode(), s.getShiftLabel(), s.isOvernightCrossesMidnight(),
                s.getApprovalStatus(), s.getRejectionReason(), s.getOrigin());
    }

    private String getPrimaryService(User user) {
        if (user == null || user.getId() == null) return null;
        List<UserServiceAssignment> assignments = userServiceAssignmentRepository.findServicesByUserId(user.getId());
        if (assignments == null || assignments.isEmpty() || assignments.get(0).getService() == null) return null;
        return assignments.get(0).getService().getName();
    }

    private String cellToString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toString()
                    : String.valueOf(cell.getNumericCellValue() == Math.floor(cell.getNumericCellValue())
                        ? String.valueOf((long) cell.getNumericCellValue()) : String.valueOf(cell.getNumericCellValue()));
            case FORMULA -> cellToStringFromFormula(cell);
            default -> cell.toString();
        };
    }

    private String cellToStringFromFormula(Cell cell) {
        try { return cell.getStringCellValue(); } catch (Exception e) {
            try { return String.valueOf(cell.getNumericCellValue()); } catch (Exception e2) { return ""; }
        }
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String[] parts = text.trim().split("[/\\-]");
            if (parts.length == 3) {
                return LocalDate.of(Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[0].trim()));
            }
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalTime parseTime(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String[] parts = text.trim().split(":");
            return LocalTime.of(Integer.parseInt(parts[0].trim()), parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0);
        } catch (Exception e) {
            return null;
        }
    }
}


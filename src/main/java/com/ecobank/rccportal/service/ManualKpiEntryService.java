package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.KpiImportResult;
import com.ecobank.rccportal.dto.ManualKpiEntryRequest;
import com.ecobank.rccportal.dto.ManualKpiEntryResponse;
import com.ecobank.rccportal.model.ManualKpiEntry;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.ManualKpiEntryRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saisies KPI manuelles (CSAT, AHT, FCR, ...) — remplace eco_kpi_manual.
 *
 * L'import Excel est un vrai "convertisseur", pas une lecture ligne à ligne
 * naïve : le classeur réel Ecobank a une feuille par équipe (RAFIKI, CIB,
 * OUTBOUND, INBOUND DIGITAL...), et DEUX mises en page différentes coexistent :
 *
 *  - "Sections empilées" : un titre de section par mois ("PERFORMANCE AGENTS
 *    CHATS- FEVRIER 2025"), un en-tête de colonnes, des lignes agent, parfois
 *    une ligne TOTAL/MOYENNE, puis la section du mois suivant plus bas.
 *  - "Mois en colonnes" : un seul grand tableau où chaque mois (Janvier,
 *    Février...) occupe un groupe de 2-3 colonnes côte à côte sur la même
 *    ligne d'agent, avec deux lignes d'en-tête (le nom du mois, puis le nom
 *    de la métrique par sous-colonne).
 *
 * Chaque feuille est analysée indépendamment, sa mise en page détectée
 * automatiquement, et toutes les feuilles du classeur sont traitées en un
 * seul import.
 */
@Service
public class ManualKpiEntryService {

    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");

    private static final Map<String, Integer> MONTH_NAME_TO_NUMBER = buildMonthMap();

    private static Map<String, Integer> buildMonthMap() {
        Map<String, Integer> m = new HashMap<>();
        String[][] names = {
                {"JANVIER", "JANV", "JAN"}, {"FEVRIER", "FEV"}, {"MARS"}, {"AVRIL", "AVR"},
                {"MAI"}, {"JUIN"}, {"JUILLET", "JUIL"}, {"AOUT", "AOU"}, {"SEPTEMBRE", "SEPT", "SEP"},
                {"OCTOBRE", "OCT"}, {"NOVEMBRE", "NOV"}, {"DECEMBRE", "DEC"}
        };
        for (int i = 0; i < names.length; i++) {
            for (String alias : names[i]) m.put(alias, i + 1);
        }
        return m;
    }

    private final ManualKpiEntryRepository manualKpiEntryRepository;
    private final UserRepository userRepository;
    private final com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository;
    private final com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final ImportIntelligenceService importIntelligenceService;
    private final AuditLogService auditLogService;
    private final LocalOcrClient localOcrClient;

    public ManualKpiEntryService(ManualKpiEntryRepository manualKpiEntryRepository, UserRepository userRepository,
                                 com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository,
                                 com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository,
                                 ImportIntelligenceService importIntelligenceService,
                                 AuditLogService auditLogService,
                                 LocalOcrClient localOcrClient) {
        this.manualKpiEntryRepository = manualKpiEntryRepository;
        this.userRepository = userRepository;
        this.rccServiceRepository = rccServiceRepository;
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
        this.importIntelligenceService = importIntelligenceService;
        this.auditLogService = auditLogService;
        this.localOcrClient = localOcrClient;
    }

    @Transactional(readOnly = true)
    public List<ManualKpiEntryResponse> listAll() {
        return manualKpiEntryRepository.findAllByOrderByPeriodDateDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ManualKpiEntryResponse> listForSubject(String username) {
        User subject = userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("Unknown user."));
        return manualKpiEntryRepository.findBySubjectOrderByPeriodDateDesc(subject).stream().map(this::toResponse).toList();
    }

    @Transactional
    public ManualKpiEntryResponse create(ManualKpiEntryRequest request, String enteredByusername) {
        if (request.subjectMatricule() == null || request.subjectMatricule().isBlank()) {
            throw ApiException.badRequest("subjectMatricule is required.");
        }
        if (request.metricCode() == null || request.metricCode().isBlank()) {
            throw ApiException.badRequest("metricCode is required.");
        }
        if (request.metricValue() == null || request.metricValue().compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("metricValue must be a non-negative number.");
        }
        if (request.periodDate() == null) throw ApiException.badRequest("periodDate is required.");

        User subject = userRepository.findFirstByUsernameIgnoreCase(request.subjectMatricule())
                .orElseThrow(() -> ApiException.badRequest("Unknown subject."));
        User enteredBy = userRepository.findFirstByUsernameIgnoreCase(enteredByusername)
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        ManualKpiEntry entry = ManualKpiEntry.builder()
                .subject(subject).enteredBy(enteredBy)
                .metricCode(request.metricCode().trim().toUpperCase())
                .metricValue(request.metricValue()).periodDate(request.periodDate())
                .build();
        return toResponse(manualKpiEntryRepository.save(entry));
    }

    /**
     * Convertit un fichier CSV en classeur POI en mémoire (une seule feuille), pour que
     * tout le parseur existant (detectWideFormatMonthRow, parseWideFormat,
     * parseRowSections...) — écrit pour des objets Sheet/Row/Cell POI — fonctionne
     * identiquement sur un CSV sans aucune duplication de logique.
     */
    /**
     * Reconstruit un tableau (classeur POI en mémoire) à partir de la sortie OCR brute
     * (voir LocalOcrClient / scripts/ocr.py). PaddleOCR ne détecte QUE du texte positionné,
     * pas une sémantique de tableau — la reconstruction se fait par regroupement géométrique :
     * les lignes de texte dont la position Y est proche (même hauteur à l'écran) forment une
     * ligne de tableau ; à l'intérieur d'une ligne, l'ordre horizontal (X croissant) donne
     * l'ordre des colonnes. Imprécis par nature sur une mise en page complexe — c'est pour
     * cela que le résultat passe ensuite par EXACTEMENT le même pipeline que l'import Excel
     * (detectWideFormatMonthRow / parseRowSections), avec le même audit de complétude : toute
     * ligne mal reconstruite ressort comme non capturée, jamais silencieusement fausse.
     */
    /** Boucle de traitement d'un classeur (toutes feuilles, détection de mise en page par
     *  feuille) — partagée entre l'import Excel/CSV et l'OCR local (buildWorkbookFromOcrGrid). */
    private void processWorkbook(Workbook workbook, YearMonth defaultPeriod, ImportContext ctx) {
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            if (sheet.getLastRowNum() < 1) continue; // feuille vide

            int wideFormatMonthRow = detectWideFormatMonthRow(sheet);
            int sheetYear = findYearInFirstRows(sheet, defaultPeriod.getYear());

            // Tableau « quelconque » (colonne agent pas en A, Nom + Prénom, colonne date, format
            // Indicateur/Valeur, colonnes descriptives…) : lu par en-têtes plutôt que par position.
            if (wideFormatMonthRow < 0) {
                SmartLayout smart = detectSmartLayout(sheet);
                if (smart != null) {
                    parseSmartTable(sheet, smart, sheetYear, defaultPeriod.atDay(1), ctx);
                    continue;
                }
            }

            if (wideFormatMonthRow >= 0) {
                parseWideFormat(sheet, wideFormatMonthRow, sheetYear, defaultPeriod.atDay(1), ctx);
            } else {
                parseRowSections(sheet, defaultPeriod.atDay(1), sheetYear, ctx);
            }
        }
    }

    private Workbook buildWorkbookFromOcrGrid(String rawJson) throws IOException {
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawJson);
        if (root.has("error")) {
            throw ApiException.serviceUnavailable("OCR local : " + root.path("error").asText());
        }
        com.fasterxml.jackson.databind.JsonNode linesNode = root.path("lines");
        if (!linesNode.isArray() || linesNode.isEmpty()) {
            throw ApiException.badRequest("Aucun texte détecté par l'OCR local sur cette image.");
        }

        record OcrLine(String text, double x, double y) {}
        List<OcrLine> lines = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode n : linesNode) {
            lines.add(new OcrLine(n.path("text").asText(""), n.path("x").asDouble(), n.path("y").asDouble()));
        }
        lines.sort(Comparator.comparingDouble(OcrLine::y));

        // Regroupement par ligne — tolérance de 15px (hauteur typique d'une ligne de texte
        // dans une capture d'écran de tableau à taille normale).
        double rowTolerance = 15.0;
        List<List<OcrLine>> rows = new ArrayList<>();
        List<OcrLine> currentRow = new ArrayList<>();
        double currentRowY = -1000;
        for (OcrLine line : lines) {
            if (currentRow.isEmpty() || Math.abs(line.y() - currentRowY) <= rowTolerance) {
                currentRow.add(line);
                currentRowY = currentRow.stream().mapToDouble(OcrLine::y).average().orElse(line.y());
            } else {
                rows.add(currentRow);
                currentRow = new ArrayList<>(List.of(line));
                currentRowY = line.y();
            }
        }
        if (!currentRow.isEmpty()) rows.add(currentRow);

        org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        Sheet sheet = workbook.createSheet("OCR");
        for (int r = 0; r < rows.size(); r++) {
            List<OcrLine> rowLines = new ArrayList<>(rows.get(r));
            rowLines.sort(Comparator.comparingDouble(OcrLine::x)); // ordre des colonnes = ordre horizontal
            Row row = sheet.createRow(r);
            for (int c = 0; c < rowLines.size(); c++) {
                String value = rowLines.get(c).text().trim();
                Cell cell = row.createCell(c);
                if (value.matches("-?\\d+([.,]\\d+)?")) {
                    cell.setCellValue(Double.parseDouble(value.replace(',', '.')));
                } else {
                    cell.setCellValue(value);
                }
            }
        }
        return workbook;
    }

    private Workbook buildWorkbookFromCsv(java.io.InputStream in) throws IOException {
        List<String> lines;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            lines = reader.lines().toList();
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Le fichier CSV est vide.");
        }

        // Détection du séparateur — ; est très courant dans les exports français (Excel FR),
        // , est le standard international. On prend celui qui apparaît le plus sur l'en-tête.
        String headerLine = lines.get(0);
        char delimiter = countOccurrences(headerLine, ';') >= countOccurrences(headerLine, ',') ? ';' : ',';

        org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Import");
        for (int r = 0; r < lines.size(); r++) {
            String[] fields = splitCsvLine(lines.get(r), delimiter);
            Row row = sheet.createRow(r);
            for (int c = 0; c < fields.length; c++) {
                String value = fields[c].trim();
                Cell cell = row.createCell(c);
                if (value.matches("-?\\d+([.,]\\d+)?")) {
                    cell.setCellValue(Double.parseDouble(value.replace(',', '.')));
                } else {
                    cell.setCellValue(value);
                }
            }
        }
        return workbook;
    }

    private long countOccurrences(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }

    /** Découpe une ligne CSV en respectant les guillemets (une valeur entre guillemets peut contenir le séparateur). */
    private String[] splitCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == delimiter && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    @Transactional
    public KpiImportResult importFromExcel(MultipartFile file, YearMonth defaultPeriod, String enteredByUsername,
                                           String serviceCode, String countryCode, String team) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Le fichier est requis.");
        }
        User enteredBy = userRepository.findFirstByUsernameIgnoreCase(enteredByUsername)
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        com.ecobank.rccportal.model.RccService importService = (serviceCode != null && !serviceCode.isBlank())
                ? rccServiceRepository.findByCodeIgnoreCase(serviceCode.trim())
                    .orElseThrow(() -> ApiException.badRequest("Unknown service: " + serviceCode))
                : null;
        String normalizedCountry = (countryCode != null && !countryCode.isBlank()) ? countryCode.trim().toUpperCase() : null;
        String normalizedTeam = (team != null && !team.isBlank()) ? team.trim().toUpperCase() : null;

        Map<String, User> usersByNormalizedName = new HashMap<>();
        for (User u : userRepository.findAll()) {
            if (u.getName() != null && !u.getName().isBlank()) {
                usersByNormalizedName.put(normalizeName(u.getName()), u);
            }
        }

        ImportContext ctx = new ImportContext(enteredBy, usersByNormalizedName, importService, normalizedCountry, normalizedTeam);

        // Format reconnu au contenu (Excel, CSV/TSV/TXT, HTML, JSON, PDF…) — voir KpiFileReader.
        // Une image envoyée ici est lue comme une capture d'écran : « peu importe le fichier ».
        KpiFileReader.Result read;
        try {
            read = KpiFileReader.read(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Impossible de lire ce fichier : " + e.getMessage());
        }
        if (read.kind() == KpiFileReader.Kind.IMAGE) {
            return importFromScreenshot(file, defaultPeriod, enteredByUsername, serviceCode, countryCode, team);
        }

        try (Workbook workbook = read.workbook()) {
            processWorkbook(workbook, defaultPeriod, ctx);
        } catch (IOException e) {
            throw ApiException.badRequest("Impossible de lire le fichier Excel : " + e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Fichier Excel invalide ou mal formé : " + e.getMessage());
        }

        String completenessNote = ctx.numericCellsDetected > 0
                ? Math.round((ctx.entriesCreated * 100.0) / ctx.numericCellsDetected) + "% du fichier capturé (" +
                  ctx.entriesCreated + "/" + ctx.numericCellsDetected + " valeurs), " + ctx.skippedValues.size() + " non capturée(s)"
                : ctx.entriesCreated + " valeur(s) capturée(s)";
        auditLogService.record(enteredByUsername, "IMPORT_EXCEL_KPI",
                "Fichier : " + (file.getOriginalFilename() != null ? file.getOriginalFilename() : "(sans nom)") +
                " — " + ctx.rowsProcessed + " ligne(s) traitée(s), " + ctx.usersAutoCreated + " compte(s) créé(s) — " + completenessNote);

        return new KpiImportResult(ctx.rowsProcessed, ctx.entriesCreated, ctx.usersAutoCreated, ctx.usersLinkedToTeam,
                new ArrayList<>(ctx.unresolvedNames),
                importIntelligenceService.summarizeAnomalies(ctx.valueAnomalies, ctx.nameResolutionNotes),
                ctx.preview, ctx.entriesCreated > ctx.preview.size(), ctx.importBatchId,
                ctx.numericCellsDetected, ctx.skippedValues);
    }

    /**
     * Import KPI depuis une capture d'écran — sans fichier Excel. Claude (vision) lit
     * l'image et en extrait un tableau structuré agent/métrique/valeur ; le reste du pipeline
     * (résolution d'agent, création de compte à la volée, rattachement filiale/service/équipe,
     * audit de complétude) est EXACTEMENT le même que importFromExcel() — même ImportContext,
     * mêmes garanties de zéro perte silencieuse.
     */
    @Transactional
    public KpiImportResult importFromScreenshot(MultipartFile image, YearMonth defaultPeriod, String enteredByUsername,
                                                 String serviceCode, String countryCode, String team) {
        if (image == null || image.isEmpty()) {
            throw ApiException.badRequest("La capture d'écran est requise.");
        }
        if (!localOcrClient.isConfigured()) {
            throw ApiException.serviceUnavailable(
                    "La lecture de capture d'écran nécessite l'OCR local (rcc.ocr.python-executable / " +
                    "rcc.ocr.script-path — voir scripts/ocr.py), non configuré ici. " +
                    "Utilisez l'import Excel/CSV classique en attendant.");
        }

        User enteredBy = userRepository.findFirstByUsernameIgnoreCase(enteredByUsername)
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        com.ecobank.rccportal.model.RccService importService = (serviceCode != null && !serviceCode.isBlank())
                ? rccServiceRepository.findByCodeIgnoreCase(serviceCode.trim())
                    .orElseThrow(() -> ApiException.badRequest("Unknown service: " + serviceCode))
                : null;
        String normalizedCountry = (countryCode != null && !countryCode.isBlank()) ? countryCode.trim().toUpperCase() : null;
        String normalizedTeam = (team != null && !team.isBlank()) ? team.trim().toUpperCase() : null;

        Map<String, User> usersByNormalizedName = new HashMap<>();
        for (User u : userRepository.findAll()) {
            if (u.getName() != null && !u.getName().isBlank()) {
                usersByNormalizedName.put(normalizeName(u.getName()), u);
            }
        }
        ImportContext ctx = new ImportContext(enteredBy, usersByNormalizedName, importService, normalizedCountry, normalizedTeam);

        // OCR local uniquement — aucune IA, aucun cloud. Passe par EXACTEMENT le même pipeline
        // que l'import Excel/CSV (processWorkbook), donc les mêmes garanties (audit de
        // complétude, détection de mise en page).
        String rawJson = localOcrClient.extractRawJson(image);
        try (Workbook workbook = buildWorkbookFromOcrGrid(rawJson)) {
            processWorkbook(workbook, defaultPeriod, ctx);
        } catch (IOException e) {
            throw ApiException.badRequest("Lecture de la capture d'écran échouée : " + e.getMessage());
        }

        recordScreenshotAudit(image, enteredByUsername, ctx, "IMPORT_SCREENSHOT_KPI_OCR_LOCAL");

        return new KpiImportResult(ctx.rowsProcessed, ctx.entriesCreated, ctx.usersAutoCreated, ctx.usersLinkedToTeam,
                new ArrayList<>(ctx.unresolvedNames),
                importIntelligenceService.summarizeAnomalies(ctx.valueAnomalies, ctx.nameResolutionNotes),
                ctx.preview, ctx.entriesCreated > ctx.preview.size(), ctx.importBatchId,
                ctx.numericCellsDetected, ctx.skippedValues);
    }

    /** Isole le tableau JSON dans une réponse Claude qui pourrait contenir du texte/markdown autour, malgré la consigne. */
    /** Journalise l'audit d'un import par capture d'écran — appelé par les deux sources
     *  possibles (OCR local ou Claude vision), avec l'action précisant laquelle a servi. */
    private void recordScreenshotAudit(MultipartFile image, String enteredByUsername, ImportContext ctx, String action) {
        String completenessNote = ctx.numericCellsDetected > 0
                ? Math.round((ctx.entriesCreated * 100.0) / ctx.numericCellsDetected) + "% de la capture capturé (" +
                  ctx.entriesCreated + "/" + ctx.numericCellsDetected + " valeurs), " + ctx.skippedValues.size() + " non capturée(s)"
                : ctx.entriesCreated + " valeur(s) capturée(s)";
        auditLogService.record(enteredByUsername, action,
                "Capture d'écran (" + (image.getOriginalFilename() != null ? image.getOriginalFilename() : "sans nom") + ") — " +
                completenessNote);
    }


    /** Supprime toutes les entrées KPI créées par un import précis — "annuler cet import". */
    @Transactional
    public long deleteImportBatch(String importBatchId) {
        if (importBatchId == null || importBatchId.isBlank()) {
            throw ApiException.badRequest("importBatchId is required.");
        }
        return manualKpiEntryRepository.deleteByImportBatchId(importBatchId);
    }

    /** Historique des imports (le plus récent en premier) — pour permettre d'en supprimer un après coup. */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.ImportBatchResponse> listImportBatches() {
        return manualKpiEntryRepository.findImportBatches().stream()
                .map(b -> new com.ecobank.rccportal.dto.ImportBatchResponse(
                        b.getBatchId(), b.getImportedAt(), b.getEntryCount(), b.getEnteredByUsername()))
                .toList();
    }

    /** État partagé entre les feuilles pendant un import (compteurs + cache des utilisateurs). */
    private class ImportContext {
        final User enteredBy;
        final Map<String, User> usersByNormalizedName;
        final com.ecobank.rccportal.model.RccService importService; // filiale/service choisis pour tout le fichier — peut être null
        final String importCountryCode;
        /** Équipe (TeamClassifier.Team) choisie pour tout le fichier — appliquée à l'ACTIVITY
         *  des agents nouvellement créés ET rattrapée sur les agents existants qui n'en ont
         *  pas encore (même logique que importService/importCountryCode). Null = pas de choix
         *  fait pour cet import, ACTIVITY inchangé. */
        final String importTeam;
        final String importBatchId = java.util.UUID.randomUUID().toString();
        int rowsProcessed = 0;
        int entriesCreated = 0;
        int usersAutoCreated = 0;
        int usersLinkedToTeam = 0;
        final Set<String> unresolvedNames = new LinkedHashSet<>();
        final List<String> valueAnomalies = new ArrayList<>();
        final List<String> nameResolutionNotes = new ArrayList<>();
        final List<com.ecobank.rccportal.dto.ImportedKpiPreview> preview = new ArrayList<>();
        /** Chaque cellule numérique du fichier NON capturée, avec sa raison exacte — voir
         *  flagSkipped(). numericCellsDetected = entriesCreated + skippedValues.size() TOUJOURS,
         *  sinon une régression a réintroduit une perte silencieuse quelque part. */
        final List<com.ecobank.rccportal.dto.KpiImportResult.SkippedValue> skippedValues = new ArrayList<>();
        int numericCellsDetected = 0;
        final int previewCap = 80;

        ImportContext(User enteredBy, Map<String, User> usersByNormalizedName,
                     com.ecobank.rccportal.model.RccService importService, String importCountryCode, String importTeam) {
            this.enteredBy = enteredBy;
            this.usersByNormalizedName = usersByNormalizedName;
            this.importService = importService;
            this.importCountryCode = importCountryCode;
            this.importTeam = importTeam;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mise en page "sections empilées" (un bloc par mois, verticalement)
    // ══════════════════════════════════════════════════════════════════════

    private void parseRowSections(Sheet sheet, LocalDate fallbackPeriod, int sheetYear, ImportContext ctx) {
        LocalDate currentPeriod = fallbackPeriod;
        List<String> metricCodes = new ArrayList<>();

        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            String firstCell = cleanText(row.getCell(0));
            if (firstCell.isBlank()) continue;

            boolean restOfRowEmpty = isRestOfRowEmpty(row);

            if (restOfRowEmpty) {
                LocalDate detected = tryExtractPeriod(firstCell, sheetYear);
                if (detected != null) currentPeriod = detected;
                continue; // titre de section (mois reconnu ou non) ou ligne isolée quelconque
            }

            // En-tête : sur un échantillon de colonnes (B à H), la majorité des cellules non vides
            // ne sont PAS numériques. Plus robuste qu'un simple test sur la colonne B seule — le
            // fichier réel a des colonnes de durée (DMT) ou des valeurs occasionnellement vides en
            // première position, qui faisaient basculer une vraie ligne de données en "en-tête".
            Boolean headerVerdict = isHeaderRow(row);
            if (Boolean.TRUE.equals(headerVerdict)) {
                metricCodes = readMetricCodes(row, 1);
                continue;
            }

            if (isTotalOrSummaryRow(firstCell)) continue;

            // Ligne de données réelle.
            ctx.rowsProcessed++;
            User subject = resolveUser(firstCell, ctx);
            if (subject == null) {
                ctx.unresolvedNames.add(firstCell);
                // Toutes les valeurs numériques de cette ligne sont perdues faute d'agent
                // résolu — chacune est comptée et journalisée, pas seulement le nom ignoré.
                for (int col = 1; col < row.getLastCellNum() && (col - 1) < metricCodes.size(); col++) {
                    Cell cell = row.getCell(col);
                    if (cellToNumber(cell) == null && cleanText(cell).isBlank()) continue; // cellule vraiment vide, rien à compter
                    String colLabel = metricCodes.get(col - 1) != null ? metricCodes.get(col - 1) : "(colonne " + col + ")";
                    flagSkipped(firstCell, colLabel, cell, "Agent non reconnu : « " + firstCell + " »", ctx);
                }
                continue;
            }

            for (int col = 1; col < row.getLastCellNum() && (col - 1) < metricCodes.size(); col++) {
                String metricCode = metricCodes.get(col - 1);
                Cell cell = row.getCell(col);
                if (metricCode == null || metricCode.isBlank()) {
                    if (cellToNumber(cell) != null || !cleanText(cell).isBlank()) {
                        flagSkipped(firstCell, "(colonne " + col + ")", cell, "Colonne sans code métrique reconnu (en-tête manquant ou vide)", ctx);
                    }
                    continue;
                }
                BigDecimal value = cellToNumber(cell);
                if (value == null) {
                    if (!cleanText(cell).isBlank()) {
                        flagSkipped(firstCell, metricCode, cell, "Valeur illisible comme nombre : « " + cleanText(cell) + " »", ctx);
                    }
                    continue;
                }
                ctx.numericCellsDetected++;
                flagIfAnomalous(subject, metricCode, value, currentPeriod, ctx);

                manualKpiEntryRepository.save(ManualKpiEntry.builder()
                        .subject(subject).enteredBy(ctx.enteredBy)
                        .metricCode(metricCode).metricValue(value).periodDate(currentPeriod)
                        .importBatchId(ctx.importBatchId)
                        .build());
                ctx.entriesCreated++;
                if (ctx.preview.size() < ctx.previewCap) {
                    ctx.preview.add(new com.ecobank.rccportal.dto.ImportedKpiPreview(
                            subject.getName(), metricCode, value, currentPeriod.toString()));
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mise en page "mois en colonnes" (un seul tableau large, un groupe de
    // colonnes par mois, une ligne par agent)
    // ══════════════════════════════════════════════════════════════════════

    /** Cherche, dans les 6 premières lignes, une ligne où au moins 2 cellules contiennent un nom de mois. */
    private int detectWideFormatMonthRow(Sheet sheet) {
        int limit = Math.min(sheet.getLastRowNum(), 6);
        for (int rowIndex = 0; rowIndex <= limit; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            if (looksLikeMonthHeaderRow(row)) return rowIndex;
        }
        return -1;
    }

    private boolean looksLikeMonthHeaderRow(Row row) {
        int hits = 0;
        for (int col = 0; col < row.getLastCellNum(); col++) {
            String text = stripAccents(cleanText(row.getCell(col)).toUpperCase());
            if (MONTH_NAME_TO_NUMBER.containsKey(text)) hits++;
        }
        return hits >= 2;
    }

    /**
     * Rubriques reconnues côté "mois en colonnes" (fichier réel Ecobank : Interactions,
     * Productivité, Score QA répétées par mois, puis une colonne "CUMUL" et des colonnes
     * finales hors mois — Note QA, Target, Score/SCORE). Utilisé pour préfixer proprement
     * les codes cumulés (CUMUL_INTERACTIONS...) sans dépendre d'une IA — comparaison de texte.
     */
    private static final Set<String> PER_MONTH_SUBMETRIC_CODES = Set.of("INTERACTIONS", "PRODUCTIVITE", "SCORE_QA");

    private void parseWideFormat(Sheet sheet, int monthRowIndex, int sheetYear, LocalDate importDefaultPeriod, ImportContext ctx) {
        Row monthRow = sheet.getRow(monthRowIndex);
        Row subHeaderRow = sheet.getRow(monthRowIndex + 1);
        if (subHeaderRow == null) return;

        // Colonnes où démarre chaque groupe "mois" reconnu, et colonne où démarre un éventuel
        // groupe "CUMUL" (bilan annuel) — les deux sont traités séparément : un groupe CUMUL ou
        // toute colonne finale (Note QA, Target, Score...) ne doit JAMAIS être avalé dans le
        // dernier mois reconnu (bug corrigé : ça faussait silencieusement Décembre auparavant).
        List<int[]> monthGroups = new ArrayList<>(); // {colonneDébut, moisNumero}
        int cumulCol = -1;
        for (int col = 1; col < monthRow.getLastCellNum(); col++) {
            String text = stripAccents(cleanText(monthRow.getCell(col)).toUpperCase());
            Integer month = MONTH_NAME_TO_NUMBER.get(text);
            if (month != null) monthGroups.add(new int[]{col, month});
            else if (text.equals("CUMUL") && cumulCol == -1) cumulCol = col;
        }
        if (monthGroups.isEmpty()) return;
        // Borne de fin du tableau mensuel : la colonne CUMUL si elle existe, sinon la fin de ligne.
        int monthlyTableEnd = cumulCol != -1 ? cumulCol : Integer.MAX_VALUE;

        for (int dataRowIndex = monthRowIndex + 2; dataRowIndex <= sheet.getLastRowNum(); dataRowIndex++) {
            Row row = sheet.getRow(dataRowIndex);
            if (row == null) continue;

            // Un second tableau "mois en colonnes" apparaît parfois plus bas dans la même feuille,
            // avec ses propres largeurs de colonnes — continuer appliquerait les colonnes du premier
            // tableau à des données qui n'ont rien à voir. On s'arrête plutôt que de deviner faux.
            if (looksLikeMonthHeaderRow(row)) break;

            String firstCell = cleanText(row.getCell(0));
            if (firstCell.isBlank() || isTotalOrSummaryRow(firstCell)) continue;

            ctx.rowsProcessed++;
            User subject = resolveUser(firstCell, ctx);
            if (subject == null) {
                ctx.unresolvedNames.add(firstCell);
                // Toutes les valeurs numériques de cette ligne (mois + CUMUL) sont perdues
                // faute d'agent résolu — chacune est comptée et journalisée.
                for (int col = 1; col < row.getLastCellNum(); col++) {
                    Cell cell = row.getCell(col);
                    if (cellToNumber(cell) == null && cleanText(cell).isBlank()) continue;
                    String colLabel = cleanText(subHeaderRow.getCell(col));
                    flagSkipped(firstCell, colLabel.isBlank() ? "(colonne " + col + ")" : colLabel, cell,
                            "Agent non reconnu : « " + firstCell + " »", ctx);
                }
                continue;
            }

            for (int g = 0; g < monthGroups.size(); g++) {
                int startCol = monthGroups.get(g)[0];
                int month = monthGroups.get(g)[1];
                int nextGroupStart = (g + 1 < monthGroups.size()) ? monthGroups.get(g + 1)[0] : row.getLastCellNum();
                int endCol = Math.min(nextGroupStart, monthlyTableEnd); // ne déborde jamais sur CUMUL/Target/Note QA
                LocalDate periodDate = LocalDate.of(sheetYear, month, 1);

                for (int col = startCol; col < endCol; col++) {
                    String metricCode = sanitizeMetricCode(cleanText(subHeaderRow.getCell(col)));
                    Cell cell = row.getCell(col);
                    if (metricCode.isBlank()) {
                        if (cellToNumber(cell) != null || !cleanText(cell).isBlank()) {
                            flagSkipped(firstCell, "(colonne " + col + ")", cell, "Colonne sans code métrique reconnu (en-tête manquant ou vide)", ctx);
                        }
                        continue;
                    }
                    BigDecimal value = cellToNumber(cell);
                    if (value == null) {
                        if (!cleanText(cell).isBlank()) {
                            flagSkipped(firstCell, metricCode, cell, "Valeur illisible comme nombre : « " + cleanText(cell) + " »", ctx);
                        }
                        continue;
                    }
                    ctx.numericCellsDetected++;
                    flagIfAnomalous(subject, metricCode, value, periodDate, ctx);

                    manualKpiEntryRepository.save(ManualKpiEntry.builder()
                            .subject(subject).enteredBy(ctx.enteredBy)
                            .metricCode(metricCode).metricValue(value).periodDate(periodDate)
                            .importBatchId(ctx.importBatchId)
                            .build());
                    ctx.entriesCreated++;
                    if (ctx.preview.size() < ctx.previewCap) {
                        ctx.preview.add(new com.ecobank.rccportal.dto.ImportedKpiPreview(
                                subject.getName(), metricCode, value, periodDate.toString()));
                    }
                }
            }

            // Colonnes hors mois (CUMUL + Note QA/Target/Score...) — rattachées à la période
            // choisie pour l'import (formulaire), jamais à Décembre ni à un mois inventé. Les
            // sous-métriques identiques à celles répétées chaque mois (Interactions/Productivité/
            // Score QA) sont préfixées CUMUL_ pour ne pas se confondre avec un mois réel ; les
            // colonnes vraiment finales (Note QA, Target, Score...) gardent leur nom tel quel.
            if (cumulCol != -1) {
                Set<String> usedCodesThisRow = new HashSet<>();
                for (int col = cumulCol; col < row.getLastCellNum(); col++) {
                    String rawCode = sanitizeMetricCode(cleanText(subHeaderRow.getCell(col)));
                    Cell cell = row.getCell(col);
                    if (rawCode.isBlank()) {
                        if (cellToNumber(cell) != null || !cleanText(cell).isBlank()) {
                            flagSkipped(firstCell, "(colonne " + col + ")", cell, "Colonne CUMUL sans code métrique reconnu (en-tête manquant ou vide)", ctx);
                        }
                        continue;
                    }
                    String metricCode = PER_MONTH_SUBMETRIC_CODES.contains(rawCode) ? "CUMUL_" + rawCode : rawCode;
                    if (!usedCodesThisRow.add(metricCode)) {
                        // même intitulé déjà vu sur cette ligne (ex. "Note QA" en double sur la
                        // feuille CIB) — on numérote plutôt que d'écraser silencieusement la valeur.
                        int suffix = 2;
                        while (!usedCodesThisRow.add(metricCode + "_" + suffix)) suffix++;
                        metricCode = metricCode + "_" + suffix;
                    }
                    BigDecimal value = cellToNumber(cell);
                    if (value == null) {
                        if (!cleanText(cell).isBlank()) {
                            flagSkipped(firstCell, metricCode, cell, "Valeur illisible comme nombre : « " + cleanText(cell) + " »", ctx);
                        }
                        continue;
                    }
                    ctx.numericCellsDetected++;
                    flagIfAnomalous(subject, metricCode, value, importDefaultPeriod, ctx);

                    manualKpiEntryRepository.save(ManualKpiEntry.builder()
                            .subject(subject).enteredBy(ctx.enteredBy)
                            .metricCode(metricCode).metricValue(value).periodDate(importDefaultPeriod)
                            .importBatchId(ctx.importBatchId)
                            .build());
                    ctx.entriesCreated++;
                    if (ctx.preview.size() < ctx.previewCap) {
                        ctx.preview.add(new com.ecobank.rccportal.dto.ImportedKpiPreview(
                                subject.getName(), metricCode, value, importDefaultPeriod.toString()));
                    }
                }
            }
        }
    }

    /** Nettoyage commun d'un intitulé de colonne en code métrique stable (accents retirés, un seul style dans tout le projet). */
    // ══════════════════════════════════════════════════════════════════════
    // Tableau quelconque, lu par ses EN-TÊTES (et non par la position des colonnes)
    // ══════════════════════════════════════════════════════════════════════

    /** Rôle de chaque colonne d'un tableau reconnu par ses en-têtes. */
    record SmartLayout(int headerRow, Integer idCol, Integer nameCol, Integer firstNameCol, Integer dateCol,
                       Integer indicatorCol, Integer valueCol, Map<Integer, String> metricCols) {
        boolean longFormat() { return indicatorCol != null && valueCol != null; }
    }

    private static final List<String> ID_HEADERS = List.of("matricule", "mat", "id agent", "identifiant", "login", "username",
            "user id", "code agent", "employee id", "id employe", "id conseiller", "agent id", "id", "code");
    private static final List<String> NAME_HEADERS = List.of("nom et prenom", "nom prenom", "nom complet", "nom & prenom", "prenom et nom",
            "prenom nom", "agent", "nom agent", "nom de l agent", "conseiller", "nom conseiller", "collaborateur", "employe", "full name",
            "name", "agent name", "nom", "last name", "utilisateur", "operateur", "teleconseiller");
    private static final List<String> FIRST_NAME_HEADERS = List.of("prenom", "prenoms", "first name", "firstname");
    private static final List<String> DATE_HEADERS = List.of("date", "jour", "mois", "periode", "period", "month", "day", "semaine",
            "week", "date d activite", "date activite");
    private static final List<String> INDICATOR_HEADERS = List.of("indicateur", "kpi", "metrique", "metric", "critere", "libelle indicateur",
            "nom indicateur", "mesure");
    private static final List<String> VALUE_HEADERS = List.of("valeur", "value", "resultat", "result", "realise", "realisation", "score", "note", "taux");
    /** Colonnes descriptives, jamais des indicateurs chiffrés. */
    private static final List<String> DIMENSION_KEYWORDS = List.of("equipe", "team", "site", "service", "filiale", "pays", "country",
            "superviseur", "supervisor", "team leader", "manager", "responsable", "activite", "campagne", "campaign", "file", "queue",
            "statut", "status", "commentaire", "comment", "observation", "remarque", "rang", "numero", "email", "mail", "telephone",
            "poste", "fonction", "role", "sexe", "genre", "contrat", "anciennete", "shift", "vacation", "tl", "n", "no", "num");

    private static String headerKey(String raw) {
        return raw == null ? "" : new ManualKpiEntryService.HeaderNormalizer().apply(raw);
    }

    /** Minuscules sans accents ni ponctuation : « N° Matricule » → « n matricule ». */
    static final class HeaderNormalizer implements java.util.function.Function<String, String> {
        @Override public String apply(String raw) {
            String t = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            return t.toLowerCase(Locale.ROOT).replace("\u00a0", " ").replaceAll("[^a-z0-9%]+", " ").trim();
        }
    }

    private static boolean matchesAny(String key, List<String> candidates) {
        if (key.isEmpty()) return false;
        for (String c : candidates) {
            if (key.equals(c) || key.startsWith(c + " ") || key.endsWith(" " + c)) return true;
        }
        return false;
    }

    /**
     * Cherche, dans les 25 premières lignes, un en-tête qui désigne l'agent (matricule et/ou nom)
     * et au moins un indicateur chiffré (ou un couple Indicateur/Valeur). Retourne null si la
     * feuille ressemble au format historique (agent en colonne A, indicateurs à droite, titres de
     * mois en sections) : le moteur historique garde alors la main.
     */
    SmartLayout detectSmartLayout(Sheet sheet) {
        int limit = Math.min(sheet.getLastRowNum(), 25);
        for (int r = 0; r <= limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null || row.getLastCellNum() < 2) continue;
            Integer idCol = null, nameCol = null, firstNameCol = null, dateCol = null, indicatorCol = null, valueCol = null;
            Map<Integer, String> metricCols = new java.util.LinkedHashMap<>();
            List<Integer> dimensionCols = new ArrayList<>();
            int textCells = 0;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                if (cell == null || cell.getCellType() != CellType.STRING) continue;
                String raw = cleanText(cell);
                String key = headerKey(raw);
                if (key.isEmpty()) continue;
                textCells++;
                if (idCol == null && matchesAny(key, ID_HEADERS) && !key.contains("nom")) { idCol = c; continue; }
                if (firstNameCol == null && matchesAny(key, FIRST_NAME_HEADERS) && !key.contains(" nom") && !key.startsWith("nom")) { firstNameCol = c; continue; }
                if (nameCol == null && matchesAny(key, NAME_HEADERS)) { nameCol = c; continue; }
                if (dateCol == null && matchesAny(key, DATE_HEADERS)) { dateCol = c; continue; }
                if (indicatorCol == null && matchesAny(key, INDICATOR_HEADERS)) { indicatorCol = c; continue; }
                if (valueCol == null && indicatorCol != null && matchesAny(key, VALUE_HEADERS)) { valueCol = c; continue; }
                if (matchesAny(key, DIMENSION_KEYWORDS)) { dimensionCols.add(c); continue; }
                String code = sanitizeMetricCode(raw);
                if (!code.isBlank()) metricCols.put(c, code);
            }
            if (idCol == null && nameCol == null) continue;
            if (textCells < 2) continue;
            boolean longFormat = indicatorCol != null && valueCol != null;
            // Au moins une ligne de données sous l'en-tête avec une valeur chiffrée dans une colonne d'indicateur.
            List<Integer> valueColumns = longFormat ? List.of(valueCol) : new ArrayList<>(metricCols.keySet());
            if (valueColumns.isEmpty() || !hasNumericBelow(sheet, r, valueColumns)) continue;

            int agentCol = idCol != null ? idCol : nameCol;
            boolean legacyShape = agentCol == 0 && firstNameCol == null && dateCol == null && !longFormat && dimensionCols.isEmpty()
                    && (idCol == null || nameCol == null);
            if (legacyShape) return null;
            return new SmartLayout(r, idCol, nameCol, firstNameCol, dateCol, indicatorCol, valueCol, metricCols);
        }
        return null;
    }

    private boolean hasNumericBelow(Sheet sheet, int headerRow, List<Integer> cols) {
        int limit = Math.min(sheet.getLastRowNum(), headerRow + 15);
        for (int r = headerRow + 1; r <= limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Integer c : cols) if (cellToNumber(row.getCell(c)) != null) return true;
        }
        return false;
    }

    private void parseSmartTable(Sheet sheet, SmartLayout layout, int sheetYear, LocalDate fallbackPeriod, ImportContext ctx) {
        Row headerRow = sheet.getRow(layout.headerRow());
        for (int r = layout.headerRow() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String matricule = layout.idCol() != null ? cellAsIdentifier(row.getCell(layout.idCol())) : "";
            String name = layout.nameCol() != null ? cleanText(row.getCell(layout.nameCol())) : "";
            if (layout.firstNameCol() != null) {
                String first = cleanText(row.getCell(layout.firstNameCol()));
                if (!first.isBlank()) name = (name + " " + first).trim();
            }
            String label = !name.isBlank() ? name : matricule;
            if (label.isBlank()) continue;
            if (isTotalOrSummaryRow(label)) continue;
            // En-tête répété (fichiers concaténés, une page par équipe…)
            if (headerRow != null && layout.nameCol() != null
                    && headerKey(label).equals(headerKey(cleanText(headerRow.getCell(layout.nameCol()))))) continue;

            LocalDate period = fallbackPeriod;
            if (layout.dateCol() != null) {
                LocalDate detected = cellToPeriod(row.getCell(layout.dateCol()), sheetYear);
                if (detected != null) period = detected;
            }

            ctx.rowsProcessed++;
            User subject = resolveUserSmart(matricule, name, ctx);
            if (subject == null) {
                ctx.unresolvedNames.add(label);
                continue;
            }

            if (layout.longFormat()) {
                String indicator = cleanText(row.getCell(layout.indicatorCol()));
                String code = sanitizeMetricCode(indicator);
                Cell valueCell = row.getCell(layout.valueCol());
                if (code.isBlank()) {
                    if (cellToNumber(valueCell) != null) flagSkipped(label, "(indicateur vide)", valueCell, "Indicateur non renseigné sur cette ligne", ctx);
                    continue;
                }
                saveSmartValue(subject, label, code, valueCell, period, ctx);
            } else {
                for (Map.Entry<Integer, String> m : layout.metricCols().entrySet()) {
                    saveSmartValue(subject, label, m.getValue(), row.getCell(m.getKey()), period, ctx);
                }
            }
        }
    }

    private void saveSmartValue(User subject, String rowLabel, String metricCode, Cell cell, LocalDate period, ImportContext ctx) {
        BigDecimal value = cellToNumber(cell);
        if (value == null) {
            String text = cleanText(cell);
            if (!text.isBlank() && !text.equals("-") && !text.equalsIgnoreCase("na")) {
                flagSkipped(rowLabel, metricCode, cell, "Valeur illisible comme nombre : « " + text + " »", ctx);
            }
            return;
        }
        ctx.numericCellsDetected++;
        flagIfAnomalous(subject, metricCode, value, period, ctx);
        manualKpiEntryRepository.save(ManualKpiEntry.builder()
                .subject(subject).enteredBy(ctx.enteredBy)
                .metricCode(metricCode.length() > 50 ? metricCode.substring(0, 50) : metricCode)
                .metricValue(value).periodDate(period)
                .importBatchId(ctx.importBatchId)
                .build());
        ctx.entriesCreated++;
        if (ctx.preview.size() < ctx.previewCap) {
            ctx.preview.add(new com.ecobank.rccportal.dto.ImportedKpiPreview(subject.getName(), metricCode, value, period.toString()));
        }
    }

    /** Matricule tel qu'écrit (un matricule numérique « 012345 » ou 12345.0 garde sa forme entière). */
    private String cellAsIdentifier(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            double d = cell.getNumericCellValue();
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return cleanText(cell);
    }

    /** Matricule d'abord (compte existant), puis nom ; sinon même logique que l'import historique (création). */
    private User resolveUserSmart(String matricule, String name, ImportContext ctx) {
        if (!matricule.isBlank()) {
            User byMatricule = userRepository.findFirstByUsernameIgnoreCase(matricule).orElse(null);
            if (byMatricule != null) return linkToTeamIfMissing(byMatricule, ctx);
        }
        if (!name.isBlank()) return resolveUser(name, ctx);
        return resolveUser(matricule, ctx);
    }

    private static final Pattern DMY = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})");
    private static final Pattern YMD = Pattern.compile("^(\\d{4})[/.-](\\d{1,2})(?:[/.-](\\d{1,2}))?");
    private static final Pattern MY = Pattern.compile("^(\\d{1,2})[/.-](\\d{4})$");

    /** Date/période d'une cellule : vraie date Excel, « 15/09/2026 », « 2026-09-15 », « 09/2026 », « septembre 2026 »… */
    LocalDate cellToPeriod(Cell cell, int sheetYear) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
        } catch (Exception ignored) { }
        String text = cleanText(cell);
        if (text.isBlank()) return null;
        try {
            Matcher m = YMD.matcher(text);
            if (m.find()) return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) != null ? Integer.parseInt(m.group(3)) : 1);
            m = DMY.matcher(text);
            if (m.find()) {
                int y = Integer.parseInt(m.group(3));
                return LocalDate.of(y < 100 ? 2000 + y : y, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            }
            m = MY.matcher(text);
            if (m.find()) return LocalDate.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)), 1);
        } catch (Exception ignored) { }
        return tryExtractPeriod(text, sheetYear);
    }

    private String sanitizeMetricCode(String headerText) {
        String code = stripAccents(headerText.toUpperCase()).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return METRIC_ALIASES.getOrDefault(code, code);
    }

    /**
     * Libellés courants des fichiers de reporting ramenés aux codes que l'Analyse de données et la
     * Performance exploitent (SCORE_QA = performance globale, INTERACTIONS et TARGET = écart à
     * l'objectif). Les autres colonnes gardent leur propre code et restent visibles par agent.
     */
    private static final Map<String, String> METRIC_ALIASES = buildMetricAliases();

    private static Map<String, String> buildMetricAliases() {
        Map<String, String> m = new HashMap<>();
        for (String a : List.of("SCORE_QUALITE", "NOTE_QUALITE", "QUALITE", "NOTE_QA", "QA", "SCORE_QUALITY", "QUALITY_SCORE",
                "EVALUATION_QA", "SCORE_QA_MOYEN", "MOYENNE_QA", "SCORE_QA_MOYENNE", "NOTE_QA_MOYENNE", "QUALITY")) m.put(a, "SCORE_QA");
        for (String a : List.of("NB_INTERACTIONS", "NOMBRE_D_INTERACTIONS", "NOMBRE_INTERACTIONS", "INTERACTIONS_TRAITEES", "APPELS_TRAITES",
                "NB_APPELS", "NOMBRE_D_APPELS", "NOMBRE_APPELS", "NB_APPELS_TRAITES", "APPELS_REPONDUS", "APPELS_DECROCHES", "CONTACTS_TRAITES",
                "VOLUME_TRAITE", "CALLS_HANDLED", "HANDLED_CALLS", "TICKETS_TRAITES", "MAILS_TRAITES", "INTERACTION")) m.put(a, "INTERACTIONS");
        for (String a : List.of("OBJECTIF", "OBJECTIFS", "CIBLE", "OBJECTIF_INTERACTIONS", "TARGET_INTERACTIONS", "OBJECTIF_MENSUEL")) m.put(a, "TARGET");
        return m;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reconnaissance commune
    // ══════════════════════════════════════════════════════════════════════

    private boolean isRestOfRowEmpty(Row row) {
        for (int col = 1; col < row.getLastCellNum(); col++) {
            if (!cleanText(row.getCell(col)).isBlank()) return false;
            if (cellToNumber(row.getCell(col)) != null) return false;
        }
        return true;
    }

    /**
     * Une ligne est un en-tête si, parmi les cellules non vides d'un échantillon (colonnes B à H),
     * moins de 40% sont numériques. Regarder plusieurs colonnes plutôt qu'une seule évite qu'une
     * cellule isolée vide ou une durée (DMT, mal typée par Excel) fasse basculer une vraie ligne
     * de données en "en-tête" — ce qui arrivait sur le fichier réel.
     */
    private Boolean isHeaderRow(Row row) {
        int sampleEnd = Math.min(row.getLastCellNum(), 8);
        int nonBlank = 0;
        int numeric = 0;
        for (int col = 1; col < sampleEnd; col++) {
            Cell cell = row.getCell(col);
            boolean hasText = !cleanText(cell).isBlank();
            BigDecimal number = cellToNumber(cell);
            if (!hasText && number == null) continue;
            nonBlank++;
            if (number != null) numeric++;
        }
        if (nonBlank == 0) return null; // pas assez d'information — on ne change pas d'avis
        return ((double) numeric / nonBlank) < 0.4;
    }

    private boolean isTotalOrSummaryRow(String firstCell) {
        String normalized = firstCell.toLowerCase();
        return normalized.contains("total") || normalized.contains("moyenne") || normalized.equals("cumul");
    }

    /** Cherche un nom de mois n'importe où dans le texte (accents ignorés) ; l'année, si absente, vient de sheetYear. */
    private LocalDate tryExtractPeriod(String text, int sheetYear) {
        String normalized = stripAccents(text.toUpperCase());
        Integer month = null;
        for (Map.Entry<String, Integer> e : MONTH_NAME_TO_NUMBER.entrySet()) {
            if (normalized.contains(e.getKey())) { month = e.getValue(); break; }
        }
        if (month == null) return null;

        Matcher ym = YEAR_PATTERN.matcher(text);
        int year = ym.find() ? Integer.parseInt(ym.group(1)) : sheetYear;
        return LocalDate.of(year, month, 1);
    }

    /** Cherche une année 20xx dans les 3 premières lignes de la feuille — sert de repli quand un titre de mois n'en a pas. */
    private int findYearInFirstRows(Sheet sheet, int fallback) {
        int limit = Math.min(sheet.getLastRowNum(), 3);
        for (int rowIndex = 0; rowIndex <= limit; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int col = 0; col < row.getLastCellNum(); col++) {
                String text = cleanText(row.getCell(col));
                Matcher m = YEAR_PATTERN.matcher(text);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        }
        return fallback;
    }

    private List<String> readMetricCodes(Row headerRow, int fromCol) {
        List<String> codes = new ArrayList<>();
        for (int col = fromCol; col < headerRow.getLastCellNum(); col++) {
            codes.add(sanitizeMetricCode(cleanText(headerRow.getCell(col))));
        }
        return codes;
    }

    /**
     * Résout l'agent par matricule/nom ; si aucun compte n'existe encore pour ce nom, en crée un
     * automatiquement (actif immédiatement) plutôt que d'ignorer la ligne — l'agent apparaîtra
     * ensuite dans Utilisateurs pour compléter sa fiche (filiale, service, rôle...).
     */
    /**
     * Détection déterministe (aucune IA) d'une valeur suspecte — ne bloque jamais l'enregistrement,
     * se contente de le signaler pour vérification humaine dans la synthèse finale.
     */
    /**
     * Journalise une cellule numérique NON capturée, avec sa raison exacte — utilisé aux 4
     * points de la reconnaissance (agent non résolu, colonne sans code métrique, cellule
     * illisible) où une valeur pourrait autrement être perdue silencieusement. Compte
     * systématiquement dans numericCellsDetected, que la cellule finisse capturée ou non.
     */
    private void flagSkipped(String rowLabel, String columnLabel, Cell cell, String reason, ImportContext ctx) {
        String rawText = cleanText(cell);
        ctx.numericCellsDetected++;
        ctx.skippedValues.add(new com.ecobank.rccportal.dto.KpiImportResult.SkippedValue(
                rowLabel, columnLabel, rawText.isBlank() ? "(valeur numérique non lisible)" : rawText, reason));
    }

    private void flagIfAnomalous(User subject, String metricCode, BigDecimal value, LocalDate period, ImportContext ctx) {
        boolean negative = value.compareTo(BigDecimal.ZERO) < 0;
        boolean looksLikePercentage = metricCode.contains("TAUX") || metricCode.contains("PCT")
                || metricCode.contains("SCORE") || metricCode.contains("CSAT") || metricCode.contains("NPS");
        boolean overHundred = looksLikePercentage && value.compareTo(BigDecimal.valueOf(100)) > 0;

        if (negative || overHundred) {
            String reason = negative ? "valeur négative" : "valeur > 100 pour un indicateur qui ressemble à un taux/score";
            ctx.valueAnomalies.add(subject.getName() + " — " + metricCode + " = " + value
                    + " (période " + period + ") : " + reason + ", à vérifier.");
        }
    }

    /**
     * Résout l'agent par matricule/nom. Si aucune correspondance exacte n'existe, tente d'abord
     * une résolution intelligente via Copilot Studio PARMI les agents déjà réels du même import
     * (jamais d'invention — voir ImportIntelligenceService) avant de se rabattre sur la création
     * d'un nouveau compte, comme auparavant.
     */
    private User resolveUser(String cellValue, ImportContext ctx) {
        User byUsername = userRepository.findFirstByUsernameIgnoreCase(cellValue).orElse(null);
        if (byUsername != null) return linkToTeamIfMissing(byUsername, ctx);

        String normalized = normalizeName(cellValue);
        User existing = ctx.usersByNormalizedName.get(normalized);
        if (existing != null) return linkToTeamIfMissing(existing, ctx);
        User similar = com.ecobank.rccportal.util.PersonNames.findUnique(cellValue, ctx.usersByNormalizedName.values(), User::getName);
        if (similar != null) return linkToTeamIfMissing(similar, ctx);

        if (importIntelligenceService.isAvailable() && !ctx.usersByNormalizedName.isEmpty()) {
            List<String> candidateNames = ctx.usersByNormalizedName.values().stream()
                    .map(User::getName).filter(n -> n != null && !n.isBlank()).distinct().toList();
            Optional<String> matched = importIntelligenceService.resolveAmbiguousName(cellValue, candidateNames);
            if (matched.isPresent()) {
                User resolvedByAi = ctx.usersByNormalizedName.get(normalizeName(matched.get()));
                if (resolvedByAi != null) {
                    ctx.nameResolutionNotes.add("« " + cellValue + " » rapproché de l'agent existant « "
                            + matched.get() + " » (matricule " + resolvedByAi.getUsername() + ") — à confirmer.");
                    return linkToTeamIfMissing(resolvedByAi, ctx);
                }
            }
        }

        User created = User.builder()
                .username(generateUsername(cellValue))
                .name(cellValue.trim().length() > 200 ? cellValue.trim().substring(0, 200) : cellValue.trim())
                .status("APPROVED")
                .accountEnabled(true)
                .accountLocked(false)
                .accountExpired(false)
                .credentialsExpired(false)
                .failedAttempts(0)
                .affiliateBranch(ctx.importCountryCode)
                .activity(ctx.importTeam)
                .build();
        created = userRepository.save(created);
        linkServiceIfMissing(created, ctx);
        ctx.usersByNormalizedName.put(normalized, created);
        ctx.usersAutoCreated++;
        return created;
    }

    /**
     * Un agent déjà en base mais sans filiale/service (créé avant qu'on connaisse cette info,
     * ou jamais rattaché) est complété avec le choix fait pour cet import — jamais écrasé s'il
     * a déjà une vraie valeur (une réaffectation se fait depuis Administration, pas un import KPI).
     */
    private User linkToTeamIfMissing(User user, ImportContext ctx) {
        boolean changed = false;
        if ((user.getAffiliateBranch() == null || user.getAffiliateBranch().isBlank()) && ctx.importCountryCode != null) {
            user.setAffiliateBranch(ctx.importCountryCode);
            changed = true;
        }
        if ((user.getActivity() == null || user.getActivity().isBlank()) && ctx.importTeam != null) {
            user.setActivity(ctx.importTeam);
            changed = true;
        }
        if (changed) {
            user = userRepository.save(user);
            ctx.usersLinkedToTeam++;
        }
        linkServiceIfMissing(user, ctx);
        return user;
    }

    private void linkServiceIfMissing(User user, ImportContext ctx) {
        if (ctx.importService == null || user.getId() == null) return;
        if (!userServiceAssignmentRepository.existsByUserIdAndServiceId(user.getId(), ctx.importService.getId())) {
            userServiceAssignmentRepository.save(com.ecobank.rccportal.model.UserServiceAssignment.builder()
                    .user(user).service(ctx.importService).build());
            ctx.usersLinkedToTeam++;
        }
    }

    /** "ESMEL Meleme Christina" -> "esmel.meleme.christina" (unique, suffixe numérique si collision). */
    /** USERNAME fait 35 caractères en base — on plafonne à 30 pour garder de la place à un éventuel suffixe de collision. */
    private String generateUsername(String fullName) {
        String base = stripAccents(fullName.trim().toLowerCase())
                .replaceAll("[^a-z0-9\\s]", "")
                .trim()
                .replaceAll("\\s+", ".");
        if (base.isBlank()) base = "agent";
        if (base.length() > 30) base = base.substring(0, 30);
        base = base.replaceAll("\\.$", ""); // évite de couper juste après un point

        String candidate = base;
        int suffix = 2;
        while (userRepository.existsByUsernameIgnoreCase(candidate)) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }

    /** Minuscules, espaces normalisés, mots triés — pour matcher "ESMEL Meleme Christina" == "Christina Meleme Esmel". */
    private String normalizeName(String name) {
        String cleaned = stripAccents(name.trim().toLowerCase()).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank()) return cleaned;
        String[] words = cleaned.split(" ");
        Arrays.sort(words);
        return String.join(" ", words);
    }

    /** Retire accents, espaces insécables (\u00a0) et espaces de largeur nulle (\u200b) — présents dans le vrai fichier. */
    private String cleanText(Cell cell) {
        if (cell == null) return "";
        String raw = switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? "" : String.valueOf((long) cell.getNumericCellValue());
            default -> "";
        };
        return raw.replace("\u200b", "").replace("\u00a0", " ").trim();
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

    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2}):(\\d{2})(?:\\s*([AaPp][Mm]))?$");

    private static final Pattern HOURS_MIN = Pattern.compile("^(\\d+)\\s*h\\s*(\\d{1,2})?\\s*(?:min|mn|m)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIN_ONLY = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*(?:min|mn)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEC_ONLY = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*(?:s|sec|secondes?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MM_SS = Pattern.compile("^(\\d{1,3}):(\\d{2})$");

    /**
     * Nombres « du monde réel » : « 85 % », « 85,5 % », « 1 234,5 », « 1.234,5 », « 1,234.5 »,
     * « 12 500 FCFA », « 1h30 », « 45 min », « 90 s », « 04:32 » (durée mm:ss, en minutes).
     * Retourne null si le texte n'est pas un nombre — la cellule est alors signalée, pas perdue.
     */
    static BigDecimal parseFlexibleNumber(String raw) {
        String t = raw.replace("\u202f", " ").replace("\u00a0", " ").trim();
        if (t.isEmpty()) return null;
        Matcher m;
        if ((m = HOURS_MIN.matcher(t)).matches()) {
            return BigDecimal.valueOf(Integer.parseInt(m.group(1)) * 60L + (m.group(2) != null ? Integer.parseInt(m.group(2)) : 0));
        }
        if ((m = MIN_ONLY.matcher(t)).matches()) return new BigDecimal(m.group(1).replace(',', '.'));
        if ((m = SEC_ONLY.matcher(t)).matches()) {
            return new BigDecimal(m.group(1).replace(',', '.')).divide(BigDecimal.valueOf(60), 4, java.math.RoundingMode.HALF_UP);
        }
        if ((m = MM_SS.matcher(t)).matches()) {
            return BigDecimal.valueOf(Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2)) / 60.0);
        }
        String n = t.replaceAll("(?i)(fcfa|xof|xaf|eur|usd|cfa|€|\\$|%|pts?|points?)", "").replace(" ", "").replace("'", "");
        if (!n.matches("[-+]?[\\d.,]+")) return null;
        int lastDot = n.lastIndexOf('.'), lastComma = n.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            n = lastComma > lastDot ? n.replace(".", "").replace(',', '.') : n.replace(",", "");
        } else if (lastComma >= 0) {
            // « 1,234 » (milliers anglo) vs « 85,5 » (décimale FR) : 3 chiffres après une virgule unique = milliers
            boolean thousands = n.indexOf(',') != lastComma || (n.length() - lastComma - 1 == 3 && n.indexOf(',') > 0 && !n.startsWith("0"));
            n = thousands ? n.replace(",", "") : n.replace(',', '.');
        } else if (lastDot >= 0 && n.indexOf('.') != lastDot) {
            n = n.replace(".", ""); // « 1.234.567 »
        }
        try {
            return new BigDecimal(n);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal cellToNumber(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(cell)) {
                    // Durée type "DMT" stockée comme heure Excel (fraction de journée) plutôt que comme
                    // vraie date de calendrier sur ce fichier — convertie en minutes pour rester exploitable.
                    java.time.LocalTime time = cell.getLocalDateTimeCellValue().toLocalTime();
                    return BigDecimal.valueOf(time.toSecondOfDay() / 60.0);
                }
                return BigDecimal.valueOf(cell.getNumericCellValue());
            }
            if (cell.getCellType() == CellType.STRING) {
                String text = cell.getStringCellValue().replace("\u200b", "").replace("\u00a0", "").trim();
                if (text.isBlank() || text.equalsIgnoreCase("N/A") || text.equals("#DIV/0!")) return null;
                BigDecimal flexible = parseFlexibleNumber(text);
                if (flexible != null) return flexible;
                Matcher timeMatch = TIME_PATTERN.matcher(text);
                if (timeMatch.matches()) {
                    int h = Integer.parseInt(timeMatch.group(1));
                    int m = Integer.parseInt(timeMatch.group(2));
                    int s = Integer.parseInt(timeMatch.group(3));
                    String meridiem = timeMatch.group(4);
                    if (meridiem != null) {
                        // Conversion 12h → 24h correcte — 12 AM = 0h (minuit), 12 PM = 12h (midi),
                        // sinon PM ajoute 12h. Une simple suppression du suffixe aurait donné un
                        // résultat FAUX (12h au lieu de 0h) plutôt qu'une valeur manquante — bien pire.
                        if (meridiem.equalsIgnoreCase("AM") && h == 12) h = 0;
                        else if (meridiem.equalsIgnoreCase("PM") && h != 12) h += 12;
                    }
                    return BigDecimal.valueOf(h * 60 + m + s / 60.0);
                }
                return new BigDecimal(text.replace(",", "."));
            }
        } catch (NumberFormatException | ArithmeticException ignored) {
            // Cellule non numérique (ex. texte libre, "#DIV/0!") — ignorée plutôt que de faire échouer tout l'import.
        }
        return null;
    }

    @Transactional
    public void remove(Integer id) {
        if (!manualKpiEntryRepository.existsById(id)) throw ApiException.notFound("KPI entry not found.");
        manualKpiEntryRepository.deleteById(id);
    }

    private ManualKpiEntryResponse toResponse(ManualKpiEntry e) {
        return new ManualKpiEntryResponse(e.getManualKpiEntryId(), e.getSubject().getUsername(), e.getSubject().getName(),
                e.getEnteredBy().getUsername(), e.getMetricCode(), e.getMetricValue(), e.getPeriodDate(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}

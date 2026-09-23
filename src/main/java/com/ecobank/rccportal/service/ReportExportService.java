package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.DataAnalysisResponse;
import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.util.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Génération de vrais fichiers bureautiques (.xlsx, .docx, à terme .pptx) pour le module
 * Analyse de données. Utilise Apache POI (déjà une dépendance du projet pour la lecture des
 * imports KPI/planning ; poi-ooxml couvre XSSF/Excel, XWPF/Word et XSLF/PowerPoint —
 * aucune nouvelle librairie nécessaire à aucune phase).
 *
 * Règle commune à tous les exports : l'IA (Azure OpenAI, via DataAnalysisService pour le
 * niveau équipe, directement via AzureOpenAiClient pour le niveau individuel) ne fait
 * QUE rédiger une synthèse à partir de chiffres déjà calculés par du code Java déterministe
 * — elle ne recalcule et n'invente jamais une valeur.
 *
 *  - Excel : "Synthèse équipe" (agrégats par service) + "Détail agents" (une ligne par agent).
 *  - Word équipe : même synthèse + tableaux, mise en forme document plutôt que tableur.
 *  - Word individuel : rapport d'un seul agent, avec sa propre synthèse IA ancrée uniquement
 *    sur ses vrais chiffres (jamais un appel IA par agent dans un rapport équipe — trop lent
 *    et inutile, un seul rapport individuel se génère à la demande, pour un agent précis).
 */
@Service
public class ReportExportService {

    private final ReportingService reportingService;
    private final DataAnalysisService dataAnalysisService;
    private final PerformanceService performanceService;
    private final AzureOpenAiClient azureOpenAiClient;

    private static final List<String> KNOWN_METRIC_CODES = List.of(
            "INTERACTIONS", "PRODUCTIVITE", "SCORE_QA", "NOTE_QA", "TARGET", "SCORE",
            "CUMUL_INTERACTIONS", "CUMUL_PRODUCTIVITE", "CUMUL_SCORE_QA"
    );

    public ReportExportService(ReportingService reportingService, DataAnalysisService dataAnalysisService,
                               PerformanceService performanceService, AzureOpenAiClient azureOpenAiClient) {
        this.reportingService = reportingService;
        this.dataAnalysisService = dataAnalysisService;
        this.performanceService = performanceService;
        this.azureOpenAiClient = azureOpenAiClient;
    }

    @Transactional(readOnly = true)
    public byte[] exportExcel(YearMonth month, String countryCode) {
        YearMonth targetMonth = month != null ? month : YearMonth.now();
        DataAnalysisResponse analysis = dataAnalysisService.analyze(targetMonth);
        List<PerformanceResponse> rows = reportingService.teamSummary(targetMonth, countryCode);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle titleStyle = titleStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle wrapStyle = wrapStyle(workbook);

            buildTeamSheet(workbook, analysis, targetMonth, titleStyle, headerStyle, wrapStyle);
            buildDetailSheet(workbook, rows, targetMonth, titleStyle, headerStyle);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de générer le fichier Excel : " + e.getMessage());
        }
    }

    private void buildTeamSheet(Workbook workbook, DataAnalysisResponse analysis, YearMonth month,
                                CellStyle titleStyle, CellStyle headerStyle, CellStyle wrapStyle) {
        Sheet sheet = workbook.createSheet("Synthèse équipe");
        int r = 0;

        Row title = sheet.createRow(r++);
        setCell(title, 0, "Analyse de données — " + month, titleStyle);
        r++; // ligne vide

        if (analysis.narrative() != null && !analysis.narrative().isBlank()) {
            Row narrativeLabel = sheet.createRow(r++);
            setCell(narrativeLabel, 0, "Synthèse" + (analysis.aiAvailable() ? " (IA)" : ""), headerStyle);
            Row narrativeRow = sheet.createRow(r++);
            Cell narrativeCell = narrativeRow.createCell(0);
            narrativeCell.setCellValue(analysis.narrative());
            narrativeCell.setCellStyle(wrapStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(narrativeRow.getRowNum(), narrativeRow.getRowNum(), 0, 4));
            narrativeRow.setHeightInPoints(90);
            r++; // ligne vide
        }

        Row global = sheet.createRow(r++);
        setCell(global, 0, "Total agents", headerStyle);
        setCell(global, 1, "Présence moy. (%)", headerStyle);
        setCell(global, 2, "Score qualité moy. (%)", headerStyle);
        setCell(global, 3, "Performance globale moy. (%)", headerStyle);
        Row globalValues = sheet.createRow(r++);
        globalValues.createCell(0).setCellValue(analysis.totalAgents());
        setNumericOrBlank(globalValues, 1, analysis.avgPresenceRate());
        setNumericOrBlank(globalValues, 2, analysis.avgQualityScore());
        setNumericOrBlank(globalValues, 3, analysis.avgPerformanceGlobale());
        r++; // ligne vide

        Row header = sheet.createRow(r++);
        setCell(header, 0, "Service", headerStyle);
        setCell(header, 1, "Nb agents", headerStyle);
        setCell(header, 2, "Présence (%)", headerStyle);
        setCell(header, 3, "Score qualité (%)", headerStyle);
        setCell(header, 4, "Performance globale (%)", headerStyle);

        for (DataAnalysisResponse.TeamBreakdown b : analysis.serviceBreakdown()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(b.serviceName());
            row.createCell(1).setCellValue(b.agentCount());
            setNumericOrBlank(row, 2, b.avgPresenceRate());
            setNumericOrBlank(row, 3, b.avgQualityScore());
            setNumericOrBlank(row, 4, b.avgPerformanceGlobale());
        }

        for (int c = 0; c <= 4; c++) sheet.autoSizeColumn(c);
    }

    private void buildDetailSheet(Workbook workbook, List<PerformanceResponse> rows, YearMonth month,
                                  CellStyle titleStyle, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Détail agents");
        int r = 0;

        Row title = sheet.createRow(r++);
        setCell(title, 0, "Détail par agent — " + month, titleStyle);
        r++; // ligne vide

        Set<String> metricCodes = new LinkedHashSet<>(KNOWN_METRIC_CODES);
        for (PerformanceResponse row : rows) {
            if (row.kpiMetrics() != null) metricCodes.addAll(row.kpiMetrics().keySet());
        }

        Row header = sheet.createRow(r++);
        int col = 0;
        setCell(header, col++, "Matricule", headerStyle);
        setCell(header, col++, "Nom", headerStyle);
        setCell(header, col++, "Filiale", headerStyle);
        setCell(header, col++, "Service", headerStyle);
        setCell(header, col++, "Présence (%)", headerStyle);
        setCell(header, col++, "Score qualité (%)", headerStyle);
        setCell(header, col++, "Nb évaluations", headerStyle);
        for (String code : metricCodes) setCell(header, col++, code, headerStyle);
        setCell(header, col, "Performance globale (%)", headerStyle);

        for (PerformanceResponse p : rows) {
            Row row = sheet.createRow(r++);
            col = 0;
            row.createCell(col++).setCellValue(nullToEmpty(p.username()));
            row.createCell(col++).setCellValue(nullToEmpty(p.userFullName()));
            row.createCell(col++).setCellValue(nullToEmpty(p.affiliateBranch()));
            row.createCell(col++).setCellValue(nullToEmpty(p.serviceName()));
            row.createCell(col++).setCellValue(p.presenceRate());
            setNumericOrBlank(row, col++, p.avgQualityScore());
            row.createCell(col++).setCellValue(p.evaluationCount());
            for (String code : metricCodes) {
                Double value = p.kpiMetrics() != null ? p.kpiMetrics().get(code) : null;
                setNumericOrBlank(row, col++, value);
            }
            setNumericOrBlank(row, col, p.performanceGlobale());
        }

        for (int c = 0; c <= 6 + metricCodes.size() + 1; c++) sheet.autoSizeColumn(c);
        if (rows.size() > 0) sheet.createFreezePane(0, 3); // sous le titre + en-tête
    }

    private void setNumericOrBlank(Row row, int col, Double value) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value); else cell.setBlank();
    }

    private void setCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private CellStyle titleStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle wrapStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 2 — Word (.docx)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Rapport équipe — synthèse IA (réutilise DataAnalysisService, même contenu que l'écran
     * Analyse de données) + tableau des agrégats par service + tableau détaillé par agent.
     * Aucun appel IA supplémentaire par agent ici : un seul appel, comme pour l'Excel.
     */
    @Transactional(readOnly = true)
    public byte[] exportWordTeam(YearMonth month, String countryCode) {
        YearMonth targetMonth = month != null ? month : YearMonth.now();
        DataAnalysisResponse analysis = dataAnalysisService.analyze(targetMonth);
        List<PerformanceResponse> rows = reportingService.teamSummary(targetMonth, countryCode);

        try (XWPFDocument doc = new XWPFDocument()) {
            addTitle(doc, "Analyse de données — " + targetMonth);

            if (analysis.narrative() != null && !analysis.narrative().isBlank()) {
                addHeading(doc, analysis.aiAvailable() ? "Synthèse (IA)" : "Synthèse");
                addParagraph(doc, analysis.narrative());
            }

            addHeading(doc, "Vue d'ensemble");
            addParagraph(doc, "Total agents : " + analysis.totalAgents()
                    + " — Présence moyenne : " + fmt(analysis.avgPresenceRate())
                    + " — Score qualité moyen : " + fmt(analysis.avgQualityScore())
                    + " — Performance globale moyenne : " + fmt(analysis.avgPerformanceGlobale()));

            addHeading(doc, "Synthèse par service");
            XWPFTable serviceTable = doc.createTable(analysis.serviceBreakdown().size() + 1, 5);
            setRow(serviceTable.getRow(0), "Service", "Nb agents", "Présence (%)", "Score qualité (%)", "Performance globale (%)");
            int r = 1;
            for (DataAnalysisResponse.TeamBreakdown b : analysis.serviceBreakdown()) {
                setRow(serviceTable.getRow(r++), b.serviceName(), String.valueOf(b.agentCount()),
                        fmt(b.avgPresenceRate()), fmt(b.avgQualityScore()), fmt(b.avgPerformanceGlobale()));
            }

            addHeading(doc, "Détail par agent");
            XWPFTable detailTable = doc.createTable(rows.size() + 1, 6);
            setRow(detailTable.getRow(0), "Matricule", "Nom", "Service", "Présence (%)", "Score qualité (%)", "Performance globale (%)");
            r = 1;
            for (PerformanceResponse p : rows) {
                setRow(detailTable.getRow(r++), nullToEmpty(p.username()), nullToEmpty(p.userFullName()),
                        nullToEmpty(p.serviceName()), String.valueOf(p.presenceRate()),
                        fmt(p.avgQualityScore()), fmt(p.performanceGlobale()));
            }

            return toBytes(doc);
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de générer le fichier Word : " + e.getMessage());
        }
    }

    /**
     * Rapport individuel — un seul agent, ses vrais chiffres du mois, plus une synthèse IA
     * ancrée strictement sur ces chiffres (jamais d'invention, jamais de comparaison à des
     * données d'autres agents non fournies). Non bloquant si l'IA échoue ou n'est pas
     * configurée : le rapport est généré quand même, sans le paragraphe de synthèse.
     */
    @Transactional(readOnly = true)
    public byte[] exportWordAgent(String username, YearMonth month) {
        YearMonth targetMonth = month != null ? month : YearMonth.now();
        PerformanceResponse p = performanceService.computeFor(username, targetMonth);

        try (XWPFDocument doc = new XWPFDocument()) {
            addTitle(doc, "Rapport de performance — " + nullToEmpty(p.userFullName()) + " — " + targetMonth);
            addParagraph(doc, "Matricule : " + nullToEmpty(p.username())
                    + " | Filiale : " + nullToEmpty(p.affiliateBranch())
                    + " | Service : " + nullToEmpty(p.serviceName()));

            String narrative = individualNarrative(p, targetMonth);
            if (narrative != null) {
                addHeading(doc, "Synthèse (IA)");
                addParagraph(doc, narrative);
            }

            addHeading(doc, "Indicateurs du mois");
            Set<String> metricCodes = new LinkedHashSet<>(KNOWN_METRIC_CODES);
            if (p.kpiMetrics() != null) metricCodes.addAll(p.kpiMetrics().keySet());

            XWPFTable table = doc.createTable(3 + metricCodes.size(), 2);
            setRow(table.getRow(0), "Présence", p.presenceRate() + " %");
            setRow(table.getRow(1), "Score qualité", fmt(p.avgQualityScore()) + " (" + p.evaluationCount() + " évaluation(s))");
            setRow(table.getRow(2), "Performance globale", fmt(p.performanceGlobale()));
            int r = 3;
            for (String code : metricCodes) {
                Double v = p.kpiMetrics() != null ? p.kpiMetrics().get(code) : null;
                setRow(table.getRow(r++), code, v != null ? String.valueOf(v) : "—");
            }

            return toBytes(doc);
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de générer le fichier Word : " + e.getMessage());
        }
    }

    private String individualNarrative(PerformanceResponse p, YearMonth month) {
        if (!azureOpenAiClient.isConfigured()) return null;

        StringBuilder data = new StringBuilder();
        data.append("Agent : ").append(nullToEmpty(p.userFullName())).append("\n");
        data.append("Mois : ").append(month).append("\n");
        data.append("Présence : ").append(p.presenceRate()).append(" %\n");
        data.append("Score qualité : ").append(fmt(p.avgQualityScore())).append(" (" + p.evaluationCount() + " évaluation(s))\n");
        data.append("Performance globale : ").append(fmt(p.performanceGlobale())).append("\n");
        if (p.kpiMetrics() != null) {
            p.kpiMetrics().forEach((code, value) -> data.append(code).append(" : ").append(value).append("\n"));
        }

        String systemPrompt = "Tu rédiges une courte synthèse (3-5 phrases) en français sur la performance " +
                "mensuelle d'un agent d'un centre d'appel Ecobank, à partir des chiffres réels ci-dessous " +
                "UNIQUEMENT — n'invente et ne compare à aucune autre donnée que celle fournie. Reste factuel, " +
                "bienveillant, et si pertinent propose un point d'attention concret pour le mois suivant.";

        try {
            return azureOpenAiClient.chat(systemPrompt, data.toString(), 0.3, 400);
        } catch (ApiException e) {
            return null; // n'empêche jamais la génération du rapport
        }
    }

    // ── Utilitaires Word ──────────────────────────────────────────────────

    private void addTitle(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(16);
    }

    private void addHeading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(200);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(13);
    }

    private void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
    }

    private void setRow(XWPFTableRow row, String... values) {
        for (int i = 0; i < values.length; i++) {
            XWPFTableCell cell = i < row.getTableCells().size() ? row.getCell(i) : row.addNewTableCell();
            cell.setText(values[i] == null ? "" : values[i]);
        }
    }

    private byte[] toBytes(XWPFDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        return out.toByteArray();
    }

    private String fmt(Double v) {
        return v != null ? v + " %" : "non renseigné";
    }

    // ══════════════════════════════════════════════════════════════════════
    // Phase 3 — PowerPoint (.pptx)
    // ══════════════════════════════════════════════════════════════════════

    private static final int SLIDE_WIDTH = 960;
    private static final int SLIDE_HEIGHT = 540;

    /**
     * Rapport équipe en diaporama — slide de titre, slide synthèse (texte IA), slide graphique
     * en barres (présence/qualité/performance par service, valeurs réelles), slide tableau
     * détaillé. Un seul appel IA (via DataAnalysisService), comme pour Excel/Word.
     */
    @Transactional(readOnly = true)
    public byte[] exportPowerPointTeam(YearMonth month, String countryCode) {
        YearMonth targetMonth = month != null ? month : YearMonth.now();
        DataAnalysisResponse analysis = dataAnalysisService.analyze(targetMonth);
        List<PerformanceResponse> rows = reportingService.teamSummary(targetMonth, countryCode);

        try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow()) {
            ppt.setPageSize(new java.awt.Dimension(SLIDE_WIDTH, SLIDE_HEIGHT));

            addTitleSlide(ppt, "Analyse de données", targetMonth.toString());

            if (analysis.narrative() != null && !analysis.narrative().isBlank()) {
                addTextSlide(ppt, analysis.aiAvailable() ? "Synthèse (IA)" : "Synthèse", analysis.narrative());
            }

            addKeyFiguresSlide(ppt, analysis);
            addServiceTableSlide(ppt, analysis);
            if (!rows.isEmpty()) addAgentTableSlide(ppt, rows);

            return toBytes(ppt);
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de générer le fichier PowerPoint : " + e.getMessage());
        }
    }

    /** Rapport individuel en diaporama — 2 slides (titre + indicateurs, synthèse IA si disponible). */
    @Transactional(readOnly = true)
    public byte[] exportPowerPointAgent(String username, YearMonth month) {
        YearMonth targetMonth = month != null ? month : YearMonth.now();
        PerformanceResponse p = performanceService.computeFor(username, targetMonth);

        try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow()) {
            ppt.setPageSize(new java.awt.Dimension(SLIDE_WIDTH, SLIDE_HEIGHT));

            addTitleSlide(ppt, nullToEmpty(p.userFullName()), targetMonth.toString());

            String narrative = individualNarrative(p, targetMonth);
            if (narrative != null) addTextSlide(ppt, "Synthèse (IA)", narrative);

            Set<String> metricCodes = new LinkedHashSet<>(KNOWN_METRIC_CODES);
            if (p.kpiMetrics() != null) metricCodes.addAll(p.kpiMetrics().keySet());

            List<String[]> indicatorRows = new java.util.ArrayList<>();
            indicatorRows.add(new String[]{"Présence", p.presenceRate() + " %"});
            indicatorRows.add(new String[]{"Score qualité", fmt(p.avgQualityScore())});
            indicatorRows.add(new String[]{"Performance globale", fmt(p.performanceGlobale())});
            for (String code : metricCodes) {
                Double v = p.kpiMetrics() != null ? p.kpiMetrics().get(code) : null;
                indicatorRows.add(new String[]{code, v != null ? String.valueOf(v) : "—"});
            }
            addTableSlide(ppt, "Indicateurs du mois", new String[]{"Indicateur", "Valeur"}, indicatorRows);

            return toBytes(ppt);
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de générer le fichier PowerPoint : " + e.getMessage());
        }
    }

    private void addTitleSlide(org.apache.poi.xslf.usermodel.XMLSlideShow ppt, String title, String subtitle) {
        org.apache.poi.xslf.usermodel.XSLFSlide slide = ppt.createSlide();
        org.apache.poi.xslf.usermodel.XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new java.awt.Rectangle(40, 180, SLIDE_WIDTH - 80, 100));
        org.apache.poi.xslf.usermodel.XSLFTextParagraph tp = titleBox.addNewTextParagraph();
        org.apache.poi.xslf.usermodel.XSLFTextRun tr = tp.addNewTextRun();
        tr.setText(title);
        tr.setFontSize(32.0);
        tr.setBold(true);

        org.apache.poi.xslf.usermodel.XSLFTextBox subBox = slide.createTextBox();
        subBox.setAnchor(new java.awt.Rectangle(40, 280, SLIDE_WIDTH - 80, 60));
        org.apache.poi.xslf.usermodel.XSLFTextParagraph sp = subBox.addNewTextParagraph();
        org.apache.poi.xslf.usermodel.XSLFTextRun sr = sp.addNewTextRun();
        sr.setText(subtitle);
        sr.setFontSize(18.0);
    }

    private void addTextSlide(org.apache.poi.xslf.usermodel.XMLSlideShow ppt, String heading, String bodyText) {
        org.apache.poi.xslf.usermodel.XSLFSlide slide = ppt.createSlide();
        addSlideHeading(slide, heading);

        org.apache.poi.xslf.usermodel.XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new java.awt.Rectangle(40, 90, SLIDE_WIDTH - 80, SLIDE_HEIGHT - 130));
        org.apache.poi.xslf.usermodel.XSLFTextParagraph p = box.addNewTextParagraph();
        org.apache.poi.xslf.usermodel.XSLFTextRun r = p.addNewTextRun();
        r.setText(bodyText);
        r.setFontSize(16.0);
        box.setWordWrap(true);
    }

    private void addKeyFiguresSlide(org.apache.poi.xslf.usermodel.XMLSlideShow ppt, DataAnalysisResponse analysis) {
        org.apache.poi.xslf.usermodel.XSLFSlide slide = ppt.createSlide();
        addSlideHeading(slide, "Vue d'ensemble");

        String[] labels = {"Total agents", "Présence moyenne", "Score qualité moyen", "Performance globale moyenne"};
        String[] values = {String.valueOf(analysis.totalAgents()), fmt(analysis.avgPresenceRate()),
                fmt(analysis.avgQualityScore()), fmt(analysis.avgPerformanceGlobale())};

        int boxWidth = (SLIDE_WIDTH - 80 - 3 * 20) / 4;
        for (int i = 0; i < labels.length; i++) {
            int x = 40 + i * (boxWidth + 20);
            org.apache.poi.xslf.usermodel.XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new java.awt.Rectangle(x, 140, boxWidth, 150));
            org.apache.poi.xslf.usermodel.XSLFTextParagraph valueP = box.addNewTextParagraph();
            org.apache.poi.xslf.usermodel.XSLFTextRun valueR = valueP.addNewTextRun();
            valueR.setText(values[i]);
            valueR.setFontSize(28.0);
            valueR.setBold(true);
            org.apache.poi.xslf.usermodel.XSLFTextParagraph labelP = box.addNewTextParagraph();
            org.apache.poi.xslf.usermodel.XSLFTextRun labelR = labelP.addNewTextRun();
            labelR.setText(labels[i]);
            labelR.setFontSize(13.0);
        }
    }

    private void addServiceTableSlide(org.apache.poi.xslf.usermodel.XMLSlideShow ppt, DataAnalysisResponse analysis) {
        List<String[]> data = analysis.serviceBreakdown().stream()
                .map(b -> new String[]{b.serviceName(), String.valueOf(b.agentCount()),
                        fmt(b.avgPresenceRate()), fmt(b.avgQualityScore()), fmt(b.avgPerformanceGlobale())})
                .toList();
        addTableSlideOnNewSlide(ppt, "Synthèse par service",
                new String[]{"Service", "Nb agents", "Présence", "Score qualité", "Performance globale"}, data);
    }

    private void addAgentTableSlide(org.apache.poi.xslf.usermodel.XMLSlideShow ppt, List<PerformanceResponse> rows) {
        // Une slide peut difficilement afficher des dizaines de lignes lisiblement — on limite
        // aux 15 premiers agents (triés alphabétiquement, comme le tableau source) et on
        // renvoie vers l'export Excel/Word pour le détail complet si l'équipe est plus grande.
        List<String[]> data = rows.stream().limit(15)
                .map(p -> new String[]{nullToEmpty(p.username()), nullToEmpty(p.userFullName()),
                        fmt(p.avgQualityScore()), fmt(p.performanceGlobale())})
                .toList();
        String heading = "Détail par agent" + (rows.size() > 15 ? " (15 sur " + rows.size() + " — voir export Excel/Word pour le détail complet)" : "");
        addTableSlideOnNewSlide(ppt, heading, new String[]{"Matricule", "Nom", "Score qualité", "Performance globale"}, data);
    }

    private void addTableSlideOnNewSlide(org.apache.poi.xslf.usermodel.XMLSlideShow ppt, String heading,
                                         String[] columns, List<String[]> dataRows) {
        org.apache.poi.xslf.usermodel.XSLFSlide slide = ppt.createSlide();
        addSlideHeading(slide, heading);
        addTableToSlide(slide, columns, dataRows);
    }

    private void addTableSlide(org.apache.poi.xslf.usermodel.XMLSlideShow ppt, String heading,
                               String[] columns, List<String[]> dataRows) {
        addTableSlideOnNewSlide(ppt, heading, columns, dataRows);
    }

    private void addTableToSlide(org.apache.poi.xslf.usermodel.XSLFSlide slide, String[] columns, List<String[]> dataRows) {
        org.apache.poi.xslf.usermodel.XSLFTable table = slide.createTable();
        table.setAnchor(new java.awt.Rectangle(40, 90, SLIDE_WIDTH - 80, Math.min(SLIDE_HEIGHT - 130, 40 * (dataRows.size() + 1))));

        org.apache.poi.xslf.usermodel.XSLFTableRow headerRow = table.addRow();
        for (String col : columns) {
            org.apache.poi.xslf.usermodel.XSLFTableCell cell = headerRow.addCell();
            org.apache.poi.xslf.usermodel.XSLFTextParagraph p = cell.addNewTextParagraph();
            org.apache.poi.xslf.usermodel.XSLFTextRun r = p.addNewTextRun();
            r.setText(col);
            r.setBold(true);
            r.setFontSize(12.0);
        }

        for (String[] rowValues : dataRows) {
            org.apache.poi.xslf.usermodel.XSLFTableRow row = table.addRow();
            for (String value : rowValues) {
                org.apache.poi.xslf.usermodel.XSLFTableCell cell = row.addCell();
                org.apache.poi.xslf.usermodel.XSLFTextParagraph p = cell.addNewTextParagraph();
                org.apache.poi.xslf.usermodel.XSLFTextRun r = p.addNewTextRun();
                r.setText(value == null ? "" : value);
                r.setFontSize(11.0);
            }
        }
    }

    private void addSlideHeading(org.apache.poi.xslf.usermodel.XSLFSlide slide, String text) {
        org.apache.poi.xslf.usermodel.XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new java.awt.Rectangle(40, 20, SLIDE_WIDTH - 80, 50));
        org.apache.poi.xslf.usermodel.XSLFTextParagraph p = box.addNewTextParagraph();
        org.apache.poi.xslf.usermodel.XSLFTextRun r = p.addNewTextRun();
        r.setText(text);
        r.setFontSize(22.0);
        r.setBold(true);
    }

    private byte[] toBytes(org.apache.poi.xslf.usermodel.XMLSlideShow ppt) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ppt.write(out);
        return out.toByteArray();
    }
}

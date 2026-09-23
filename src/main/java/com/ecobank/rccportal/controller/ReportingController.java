package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.ReportingService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Vue d'équipe agrégée (tous les agents) — réservé QA/ADMIN. */
@RestController
@RequestMapping("/api/reporting")
public class ReportingController {

    private final ReportingService reportingService;
    private final com.ecobank.rccportal.service.ReportExportService reportExportService;

    public ReportingController(ReportingService reportingService,
                               com.ecobank.rccportal.service.ReportExportService reportExportService) {
        this.reportingService = reportingService;
        this.reportExportService = reportExportService;
    }

    @GetMapping("/team")
    public List<PerformanceResponse> team(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String countryCode) {
        requireReviewer(requester);
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            try {
                java.time.LocalDate periodStart = java.time.LocalDate.parse(from);
                java.time.LocalDate periodEnd = java.time.LocalDate.parse(to);
                String label = from.equals(to) ? from : (from + " → " + to);
                return reportingService.teamSummary(periodStart, periodEnd, label, countryCode);
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("from/to must be ISO dates (YYYY-MM-DD).");
            }
        }
        return reportingService.teamSummary(parseMonth(month), countryCode);
    }

    @GetMapping("/team/export")
    public ResponseEntity<byte[]> exportTeam(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam(required = false) String month) {
        requireReviewer(requester);
        YearMonth targetMonth = parseMonth(month) != null ? parseMonth(month) : YearMonth.now();
        String csv = reportingService.exportCsv(targetMonth);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);

        String filename = "reporting-" + targetMonth + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    /**
     * Export Excel (.xlsx) — Phase 1 du module de génération de rapports : synthèse équipe
     * (agrégats par service, même contenu que "Analyse de données") + détail par agent, dans
     * un vrai classeur exploitable, pas juste un CSV.
     */
    @GetMapping("/team/export-excel")
    public ResponseEntity<byte[]> exportTeamExcel(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String countryCode) {
        requireReviewer(requester);
        YearMonth targetMonth = parseMonth(month) != null ? parseMonth(month) : YearMonth.now();
        byte[] body = reportExportService.exportExcel(targetMonth, countryCode);

        String filename = "analyse-donnees-" + targetMonth + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    /**
     * Export Word (.docx) équipe — Phase 2 du module de génération de rapports : même
     * synthèse IA que l'écran Analyse de données, mise en forme document plutôt que tableur.
     */
    @GetMapping("/team/export-word")
    public ResponseEntity<byte[]> exportTeamWord(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String countryCode) {
        requireReviewer(requester);
        YearMonth targetMonth = parseMonth(month) != null ? parseMonth(month) : YearMonth.now();
        byte[] body = reportExportService.exportWordTeam(targetMonth, countryCode);
        return wordResponse(body, "analyse-donnees-" + targetMonth + ".docx");
    }

    /**
     * Export Word (.docx) individuel — rapport d'un agent précis, réservé QA/RH/Admin (l'agent
     * télécharge le sien via /api/performance/me/export-word, sans cette restriction).
     */
    @GetMapping("/agent/export-word")
    public ResponseEntity<byte[]> exportAgentWord(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam String matricule,
            @RequestParam(required = false) String month) {
        requireReviewer(requester);
        YearMonth targetMonth = parseMonth(month) != null ? parseMonth(month) : YearMonth.now();
        byte[] body = reportExportService.exportWordAgent(matricule, targetMonth);
        return wordResponse(body, "rapport-" + matricule + "-" + targetMonth + ".docx");
    }

    private ResponseEntity<byte[]> wordResponse(byte[] body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(body);
    }

    /** Export PowerPoint (.pptx) équipe — Phase 3 : titre, synthèse IA, chiffres clés, graphes/tableaux. */
    @GetMapping("/team/export-powerpoint")
    public ResponseEntity<byte[]> exportTeamPowerPoint(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String countryCode) {
        requireReviewer(requester);
        YearMonth targetMonth = parseMonth(month) != null ? parseMonth(month) : YearMonth.now();
        byte[] body = reportExportService.exportPowerPointTeam(targetMonth, countryCode);
        return pptResponse(body, "analyse-donnees-" + targetMonth + ".pptx");
    }

    /** Export PowerPoint individuel — réservé QA/RH/Admin (l'agent télécharge le sien via /api/performance/me/export-powerpoint). */
    @GetMapping("/agent/export-powerpoint")
    public ResponseEntity<byte[]> exportAgentPowerPoint(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam String matricule,
            @RequestParam(required = false) String month) {
        requireReviewer(requester);
        YearMonth targetMonth = parseMonth(month) != null ? parseMonth(month) : YearMonth.now();
        byte[] body = reportExportService.exportPowerPointAgent(matricule, targetMonth);
        return pptResponse(body, "rapport-" + matricule + "-" + targetMonth + ".pptx");
    }

    private ResponseEntity<byte[]> pptResponse(byte[] body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                .body(body);
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("month must be in YYYY-MM format.");
        }
    }

    /**
     * QA ou ADMIN — même normalisation du champ "service" que UserController
     * (stocké en base sous forme de code, ex. "QUALITY_ASSURANCE").
     */
    private void requireReviewer(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isRh = requester != null && "rh".equalsIgnoreCase(requester.role());
        boolean isSupervisor = requester != null && "supervisor".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isRh && !isSupervisor && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance, Human Resources, a supervisor, or an administrator can view team reporting.");
        }
    }
}

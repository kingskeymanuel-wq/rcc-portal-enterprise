package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.PerformanceService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * Indicateurs personnels de l'agent connecté pour un mois donné (KPI
 * manuels, qualité, présence, score global). Pas de restriction de rôle :
 * chacun ne voit que ses propres données (résolues via le JWT).
 */
@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    private final PerformanceService performanceService;
    private final com.ecobank.rccportal.service.ReportExportService reportExportService;

    public PerformanceController(PerformanceService performanceService,
                                 com.ecobank.rccportal.service.ReportExportService reportExportService) {
        this.performanceService = performanceService;
        this.reportExportService = reportExportService;
    }

    /** month au format "YYYY-MM" ; par défaut le mois en cours. */
    @GetMapping("/me")
    public PerformanceResponse me(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam(required = false) String month) {

        return performanceService.computeFor(requester.username(), parseMonth(month));
    }

    /** Rapport Word personnel — Phase 2 du module de génération de rapports, avec synthèse IA
     *  ancrée uniquement sur les propres chiffres de l'agent connecté. */
    @GetMapping("/me/export-word")
    public org.springframework.http.ResponseEntity<byte[]> exportMyReportWord(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam(required = false) String month) {
        YearMonth targetMonth = parseMonth(month) != null ? parseMonth(month) : YearMonth.now();
        byte[] body = reportExportService.exportWordAgent(requester.username(), targetMonth);
        String filename = "mon-rapport-" + targetMonth + ".docx";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(body);
    }

    /** Rapport PowerPoint personnel — Phase 3, mêmes garanties que le Word (IA jamais inventée). */
    @GetMapping("/me/export-powerpoint")
    public org.springframework.http.ResponseEntity<byte[]> exportMyReportPowerPoint(
            @AuthenticationPrincipal AuthenticatedUser requester,
            @RequestParam(required = false) String month) {
        YearMonth targetMonth = parseMonth(month) != null ? parseMonth(month) : YearMonth.now();
        byte[] body = reportExportService.exportPowerPointAgent(requester.username(), targetMonth);
        String filename = "mon-rapport-" + targetMonth + ".pptx";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
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
}

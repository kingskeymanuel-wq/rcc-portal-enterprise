package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.KpiImportResult;
import com.ecobank.rccportal.dto.ManualKpiEntryRequest;
import com.ecobank.rccportal.dto.ManualKpiEntryResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.ManualKpiEntryService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/kpi/manual-entries")
public class ManualKpiEntryController {

    private final ManualKpiEntryService manualKpiEntryService;

    public ManualKpiEntryController(ManualKpiEntryService manualKpiEntryService) {
        this.manualKpiEntryService = manualKpiEntryService;
    }

    @GetMapping("/me")
    public List<ManualKpiEntryResponse> mine(@AuthenticationPrincipal AuthenticatedUser requester) {
        return manualKpiEntryService.listForSubject(requester.username());
    }

    @GetMapping
    public List<ManualKpiEntryResponse> listAll(@RequestParam(required = false) String subjectMatricule,
                                                 @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return subjectMatricule != null
                ? manualKpiEntryService.listForSubject(subjectMatricule)
                : manualKpiEntryService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ManualKpiEntryResponse create(@Valid @RequestBody ManualKpiEntryRequest request,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return manualKpiEntryService.create(request, requester.username());
    }

    /** period au format "YYYY-MM" — s'applique à toutes les lignes du fichier.
     *  serviceCode/countryCode (optionnels) : filiale/service auxquels rattacher tout le fichier —
     *  appliqués aux agents nouvellement créés ET rattrapés sur les agents existants qui n'en ont pas encore. */
    @PostMapping(value = "/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public KpiImportResult importExcel(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                       @RequestParam String period,
                                       @RequestParam(required = false) String serviceCode,
                                       @RequestParam(required = false) String countryCode,
                                       @RequestParam(required = false) String team,
                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        java.time.YearMonth yearMonth;
        try {
            yearMonth = java.time.YearMonth.parse(period);
        } catch (Exception e) {
            throw ApiException.badRequest("period must be in YYYY-MM format.");
        }
        return manualKpiEntryService.importFromExcel(file, yearMonth, requester.username(), serviceCode, countryCode, team);
    }

    /** Même principe que /import, mais depuis une capture d'écran (image) analysée par Claude
     *  (vision) au lieu d'un fichier Excel/CSV structuré. Voir ManualKpiEntryService.importFromScreenshot(). */
    @PostMapping(value = "/import-screenshot", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public KpiImportResult importScreenshot(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                            @RequestParam String period,
                                            @RequestParam(required = false) String serviceCode,
                                            @RequestParam(required = false) String countryCode,
                                            @RequestParam(required = false) String team,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        java.time.YearMonth yearMonth;
        try {
            yearMonth = java.time.YearMonth.parse(period);
        } catch (Exception e) {
            throw ApiException.badRequest("period must be in YYYY-MM format.");
        }
        return manualKpiEntryService.importFromScreenshot(file, yearMonth, requester.username(), serviceCode, countryCode, team);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        manualKpiEntryService.remove(id);
    }

    /** Historique des imports Excel passés — pour pouvoir en supprimer un après coup. */
    @GetMapping("/imports")
    public List<com.ecobank.rccportal.dto.ImportBatchResponse> listImports(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return manualKpiEntryService.listImportBatches();
    }

    /** Supprime toutes les valeurs créées par un import précis — annule l'import en un clic. */
    @DeleteMapping("/imports/{batchId}")
    public java.util.Map<String, Object> deleteImport(@PathVariable String batchId,
                                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        long deleted = manualKpiEntryService.deleteImportBatch(batchId);
        return java.util.Map.of("deletedCount", deleted);
    }

    /** Lecture/supervision d'équipe — reste accessible à QA et admin. */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can view this.");
        }
    }

    /** ⚠️ Écriture (saisie/import/suppression) — réservée à la Quality Assurance, l'admin s'occupe des réglages. */
    /** Nom conservé pour compatibilité — autorise en réalité QA ET admin, cohérent avec la visibilité
     *  du formulaire côté frontend (isSupervisor = QA ou Admin). */
    private void requireQaOnly(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can manage KPI entries.");
        }
    }
}

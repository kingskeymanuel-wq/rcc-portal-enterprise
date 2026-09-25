package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.CampaignFileModelService;
import com.ecobank.rccportal.service.CampaignPerformanceService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Campagne créée depuis un fichier (analyse → création + import) et suivi de performance Team Leader. */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignFileController {

    private final CampaignFileModelService fileModelService;
    private final CampaignPerformanceService performanceService;

    public CampaignFileController(CampaignFileModelService fileModelService, CampaignPerformanceService performanceService) {
        this.fileModelService = fileModelService;
        this.performanceService = performanceService;
    }

    /** Étape 1 : colonnes reconnues + questionnaire déduit du fichier (rien n'est enregistré). */
    @PostMapping(value = "/from-file/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CampaignFileModelService.FileAnalysis analyze(@RequestParam("file") MultipartFile file,
                                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        return fileModelService.analyze(requester, file);
    }

    /** Étape 2 : création de la campagne avec le questionnaire validé, puis import des contacts. */
    @PostMapping(value = "/from-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CampaignFileModelService.CreateFromFileResult create(@RequestParam("file") MultipartFile file,
                                                                @RequestPart("request") CampaignFileModelService.CreateFromFileRequest request,
                                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        return fileModelService.create(requester, file, request);
    }

    @GetMapping("/{id}/performance")
    public CampaignPerformanceService.Performance performance(@PathVariable Integer id, @RequestParam(required = false) Integer days,
                                                              @AuthenticationPrincipal AuthenticatedUser requester) {
        return performanceService.performance(requester, id, days);
    }
}

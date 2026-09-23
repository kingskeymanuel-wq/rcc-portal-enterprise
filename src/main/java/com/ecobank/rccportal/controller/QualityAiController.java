package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.AnalyzeRequest;
import com.ecobank.rccportal.dto.QualityAnalysisResponse;
import com.ecobank.rccportal.dto.TranscriptionResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.QualityAiService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Transcription (Vosk embarqué) et analyse (moteur maison) des écoutes.
 * Rien n'est persisté : chaque appel recalcule le résultat à la volée.
 * Réservé QA/admin, même règle que le reste du module Clairaudio.
 */
@RestController
@RequestMapping("/api/quality/evaluations/{evaluationId}/ai")
public class QualityAiController {

    private final QualityAiService qualityAiService;

    public QualityAiController(QualityAiService qualityAiService) {
        this.qualityAiService = qualityAiService;
    }

    @PostMapping("/transcribe")
    public TranscriptionResponse transcribe(@PathVariable Integer evaluationId,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return qualityAiService.transcribe(evaluationId);
    }

    /** La transcription est envoyée dans le corps de la requête — rien n'est relu en base. */
    @PostMapping("/analyze")
    public QualityAnalysisResponse analyze(@PathVariable Integer evaluationId,
                                           @RequestBody AnalyzeRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return qualityAiService.analyze(evaluationId, request.transcript());
    }

    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can use AI transcription/analysis.");
        }
    }
}

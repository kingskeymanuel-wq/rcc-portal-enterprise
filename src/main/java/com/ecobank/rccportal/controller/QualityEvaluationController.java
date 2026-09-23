package com.ecobank.rccportal.controller;
import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AudioStorageService;
import com.ecobank.rccportal.service.QualityEvaluationService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

/** Portage du module Clairaudio (qualité des appels). */
@RestController
@RequestMapping("/api/quality")
public class QualityEvaluationController {
    private final QualityEvaluationService qualityEvaluationService;
    private final AudioStorageService audioStorageService;

    public QualityEvaluationController(QualityEvaluationService qualityEvaluationService,
                                       AudioStorageService audioStorageService) {
        this.qualityEvaluationService = qualityEvaluationService;
        this.audioStorageService = audioStorageService;
    }

    @GetMapping("/criteria")
    public List<QualityCriterionResponse> listCriteria() {
        return qualityEvaluationService.listCriteria();
    }

    @GetMapping("/motifs")
    public List<QualityMotifResponse> listMotifs() {
        return qualityEvaluationService.listMotifs();
    }

    @GetMapping("/evaluations")
    public List<QualityEvaluationResponse> listAll(@RequestParam(required = false) String agentMatricule) {
        return agentMatricule != null
                ? qualityEvaluationService.listForAgent(agentMatricule)
                : qualityEvaluationService.listAll();
    }

    @GetMapping("/evaluations/{id}")
    public QualityEvaluationResponse getById(@PathVariable Integer id) {
        return qualityEvaluationService.getById(id);
    }

    /** Upload de l'enregistrement — retourne le chemin à réutiliser comme recordingRef à la création. */
    @PostMapping(value = "/recordings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> uploadRecording(@RequestParam("file") MultipartFile file,
                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return Map.of("recordingRef", audioStorageService.store(file));
    }

    /** ⚠️ Réservé QA/admin — seul un évaluateur qualité peut créer une évaluation. */
    @PostMapping("/evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    public QualityEvaluationResponse create(@Valid @RequestBody QualityEvaluationRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return qualityEvaluationService.create(request, requester);
    }

    /** ⚠️ Réservé QA/admin. */
    @PutMapping("/evaluations/{id}")
    public QualityEvaluationResponse update(@PathVariable Integer id, @RequestBody QualityEvaluationRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return qualityEvaluationService.update(id, request, requester);
    }

    /** ⚠️ Réservé QA/admin. */
    @DeleteMapping("/evaluations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        qualityEvaluationService.remove(id, requester);
    }

    /** ⚠️ Réservé à la Quality Assurance (et son Superviseur, qui supervise les écoutes) — l'admin s'occupe des réglages, pas du contenu des évaluations. */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isQa = requester != null && requester.service() != null
                && java.util.Set.of("quality assurance", "superviseur qa").contains(requester.service().toLowerCase().replace('_', ' '));
        if (!isQa) {
            throw ApiException.forbidden("Only Quality Assurance can manage quality evaluations.");
        }
    }
}
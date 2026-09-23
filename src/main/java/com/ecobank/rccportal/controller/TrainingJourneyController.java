package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.TrainingJourneyDtos.Certificate;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.CertificateDecision;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.CertificateRequest;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.QaOverview;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.Summary;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.Verification;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.TrainingJourneyService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * Espace Formation façon EduFun (voir TrainingJourneyService) : tableau de bord de l'agent,
 * certificats (demande agent → décision QA → vérification par numéro) et vue QA synchronisée.
 */
@RestController
@RequestMapping("/api/training/journey")
public class TrainingJourneyController {

    private final TrainingJourneyService service;

    public TrainingJourneyController(TrainingJourneyService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public Summary me(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.summary(username(requester));
    }

    @GetMapping("/certificates/me")
    public List<Certificate> myCertificates(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.myCertificates(username(requester));
    }

    @PostMapping("/certificates")
    @ResponseStatus(HttpStatus.CREATED)
    public Certificate requestCertificate(@RequestBody CertificateRequest request,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.requestCertificate(username(requester), request.sourceType(), request.sourceId());
    }

    @GetMapping("/certificates/verify/{number}")
    public Verification verify(@PathVariable String number) {
        return service.verify(number);
    }

    @GetMapping("/certificates")
    public List<Certificate> listCertificates(@RequestParam(required = false) String status,
                                              @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.listCertificates(status);
    }

    @PostMapping("/certificates/{id}/decision")
    public Certificate decide(@PathVariable Long id, @RequestBody CertificateDecision decision,
                              @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.decide(id, decision.action(), decision.note(),
                requester.name() != null ? requester.name() : requester.username());
    }

    @GetMapping("/qa/overview")
    public QaOverview qaOverview(@RequestParam(required = false) String team,
                                 @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.qaOverview(team);
    }

    private static String username(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) throw ApiException.unauthorized("Utilisateur non authentifié.");
        return requester.username();
    }

    /** Même règle que TrainingApiController / CourseController. */
    private static void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && Set.of("quality assurance", "superviseur qa", "formateur").contains(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Réservé à la Quality Assurance ou à un administrateur.");
        }
    }
}

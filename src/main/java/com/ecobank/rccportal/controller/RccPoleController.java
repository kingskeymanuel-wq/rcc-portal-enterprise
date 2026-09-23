package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.RccPoleRequest;
import com.ecobank.rccportal.dto.RccPoleResponse;
import com.ecobank.rccportal.dto.SlaRuleRequest;
import com.ecobank.rccportal.dto.SlaRuleResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.RccPoleService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Organigramme des pôles RCC (Inbound, Outbound, Résolution, Opérations, Business,
 * Agences) — fiche par pôle (manager, contact, description) et activités/délais
 * rattachées. Lecture ouverte à tout agent authentifié ; écriture (fiches et
 * activités) et alerte manager réservées à QA/ADMIN — voir requireQaOrAdmin.
 */
@RestController
@RequestMapping("/api/rcc-poles")
public class RccPoleController {

    private final RccPoleService poleService;

    public RccPoleController(RccPoleService poleService) {
        this.poleService = poleService;
    }

    @GetMapping
    public List<RccPoleResponse> list(@RequestParam(required = false, defaultValue = "false") boolean includeInactive,
                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        if (includeInactive) {
            requireQaOrAdmin(requester);
            return poleService.listAll();
        }
        return poleService.listActive();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RccPoleResponse create(@Valid @RequestBody RccPoleRequest request,
                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return poleService.create(request);
    }

    @PutMapping("/{id}")
    public RccPoleResponse update(@PathVariable Integer id, @Valid @RequestBody RccPoleRequest request,
                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return poleService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        poleService.delete(id);
    }

    // ---------- Activités & délais du pôle ----------

    @GetMapping("/{id}/activities")
    public List<SlaRuleResponse> listActivities(@PathVariable Integer id) {
        return poleService.listActivities(id);
    }

    @PostMapping("/{id}/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public SlaRuleResponse addActivity(@PathVariable Integer id, @Valid @RequestBody SlaRuleRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return poleService.addActivity(id, request);
    }

    @PutMapping("/{id}/activities/{ruleId}")
    public SlaRuleResponse updateActivity(@PathVariable Integer id, @PathVariable Integer ruleId,
                                           @Valid @RequestBody SlaRuleRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return poleService.updateActivity(id, ruleId, request);
    }

    @DeleteMapping("/{id}/activities/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteActivity(@PathVariable Integer id, @PathVariable Integer ruleId,
                                @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        poleService.deleteActivity(id, ruleId);
    }

    // ---------- Assignation / alerte au manager du pôle ----------

    @PostMapping("/{id}/alert-manager")
    public Map<String, String> alertManager(@PathVariable Integer id, @RequestBody(required = false) Map<String, String> body,
                                             @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        String message = body != null ? body.get("message") : null;
        poleService.alertManager(id, message, requester);
        return Map.of("status", "sent");
    }

    /** Même règle que ReportingController.requireReviewer, restreinte à QA/ADMIN (pas RH/Supervisor) :
     *  QA n'est pas un rôle mais un service normalisé — voir session.js computeProfile côté client. */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Seuls QA et Administration peuvent modifier les fiches pôle.");
        }
    }
}

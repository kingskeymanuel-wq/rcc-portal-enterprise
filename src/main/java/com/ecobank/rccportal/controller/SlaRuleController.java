package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.SlaRuleRequest;
import com.ecobank.rccportal.dto.SlaRuleResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.SlaRuleService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Référentiel des SLA RCC (délais de traitement communiqués au client par motif de
 * réclamation/demande). Lecture ouverte à tout agent authentifié (utile pour
 * consultation directe, et alimente RAF côté serveur) ; écriture réservée aux admins.
 */
@RestController
@RequestMapping("/api/sla-rules")
public class SlaRuleController {

    private final SlaRuleService slaRuleService;

    public SlaRuleController(SlaRuleService slaRuleService) {
        this.slaRuleService = slaRuleService;
    }

    @GetMapping
    public List<SlaRuleResponse> list(@RequestParam(required = false, defaultValue = "false") boolean includeInactive,
                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        if (includeInactive) {
            requireAdmin(requester);
            return slaRuleService.listAll();
        }
        return slaRuleService.listActive();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SlaRuleResponse create(@Valid @RequestBody SlaRuleRequest request,
                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return slaRuleService.create(request);
    }

    @PutMapping("/{id}")
    public SlaRuleResponse update(@PathVariable Integer id, @Valid @RequestBody SlaRuleRequest request,
                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return slaRuleService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        slaRuleService.delete(id);
    }

    private void requireAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        if (!isAdmin) {
            throw ApiException.forbidden("Seul un administrateur peut modifier le référentiel SLA.");
        }
    }
}

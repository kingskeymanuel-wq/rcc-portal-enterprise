package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.AgentDossierRequest;
import com.ecobank.rccportal.dto.AgentDossierResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AgentDossierService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agent-dossiers")
public class AgentDossierController {

    private final AgentDossierService agentDossierService;

    public AgentDossierController(AgentDossierService agentDossierService) {
        this.agentDossierService = agentDossierService;
    }

    /** Liste complète — réservée à la QA/admin (peut contenir des données client bancaires). */
    @GetMapping
    public List<AgentDossierResponse> listAll(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return agentDossierService.listAll();
    }

    @GetMapping("/me")
    public List<AgentDossierResponse> mine(@AuthenticationPrincipal AuthenticatedUser requester) {
        return agentDossierService.listForUser(requester.username());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDossierResponse create(@Valid @RequestBody AgentDossierRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return agentDossierService.create(request);
    }

    @PutMapping("/{id}")
    public AgentDossierResponse update(@PathVariable Integer id, @RequestBody AgentDossierRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return agentDossierService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        agentDossierService.remove(id);
    }

    private void requireQaOrAdmin(AuthenticatedUser requester) {
        if (!"admin".equalsIgnoreCase(requester.role()) && !(requester.service() != null && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' ')))) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can manage agent dossiers.");
        }
    }
}

package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.ProcedureWorkflowService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Parcours interactif d'une procédure — consultation ouverte à tout compte
 * connecté (agent/QA/admin), création/édition réservée à QA/admin.
 */
@RestController
@RequestMapping("/api/procedures")
public class ProcedureWorkflowController {

    private final ProcedureWorkflowService workflowService;

    public ProcedureWorkflowController(ProcedureWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping("/{procedureId}/workflow")
    public List<ProcedureWorkflowNodeResponse> listNodes(@PathVariable Integer procedureId) {
        return workflowService.listNodes(procedureId);
    }

    @GetMapping("/{procedureId}/workflow/start")
    public ProcedureWorkflowNodeResponse getStartNode(@PathVariable Integer procedureId) {
        return workflowService.getStartNode(procedureId);
    }

    @GetMapping("/workflow/nodes/{nodeId}")
    public ProcedureWorkflowNodeResponse getNode(@PathVariable Integer nodeId) {
        return workflowService.getNode(nodeId);
    }

    @PostMapping("/{procedureId}/workflow/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public ProcedureWorkflowNodeResponse createNode(@PathVariable Integer procedureId,
                                                    @RequestBody CreateWorkflowNodeRequest request,
                                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return workflowService.createNode(procedureId, request);
    }

    @PostMapping("/workflow/options")
    @ResponseStatus(HttpStatus.CREATED)
    public ProcedureWorkflowOptionResponse createOption(@RequestBody CreateWorkflowOptionRequest request,
                                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return workflowService.createOption(request);
    }

    /** ⚠️ Contenu (nœuds/options du parcours) — réservé à la Quality Assurance, l'admin s'occupe des réglages. */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isQa = (requester.service() != null && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' ')));
        if (!isQa) {
            throw ApiException.forbidden("Only Quality Assurance can edit a workflow.");
        }
    }
}
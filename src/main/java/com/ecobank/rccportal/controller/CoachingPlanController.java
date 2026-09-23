package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.CoachingPlanRequest;
import com.ecobank.rccportal.dto.CoachingPlanResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.CoachingPlanService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/coaching-plans")
public class CoachingPlanController {

    private final CoachingPlanService coachingPlanService;

    public CoachingPlanController(CoachingPlanService coachingPlanService) {
        this.coachingPlanService = coachingPlanService;
    }

    @GetMapping
    public List<CoachingPlanResponse> listAll(@RequestParam(required = false) String agentMatricule,
                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        // Un agent ne voit que ses propres plans ; QA/admin peuvent tout voir ou filtrer par agent.
        if (agentMatricule != null) {
            requireQaOrAdminUnlessSelf(requester, agentMatricule);
            return coachingPlanService.listForAgent(agentMatricule);
        }
        requireQaOrAdmin(requester);
        return coachingPlanService.listAll();
    }

    @GetMapping("/me")
    public List<CoachingPlanResponse> myPlans(@AuthenticationPrincipal AuthenticatedUser requester) {
        return coachingPlanService.listForAgent(requester.username());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CoachingPlanResponse create(@Valid @RequestBody CoachingPlanRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return coachingPlanService.create(request);
    }

    @PutMapping("/{id}")
    public CoachingPlanResponse update(@PathVariable Integer id, @RequestBody CoachingPlanRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return coachingPlanService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        coachingPlanService.remove(id);
    }

    /** Création/modification des plans de coaching réservée à la QA et aux admins (pas aux agents eux-mêmes). */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        if (!"admin".equalsIgnoreCase(requester.role()) && !(requester.service() != null && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' ')))) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can manage coaching plans.");
        }
    }

    private void requireQaOrAdminUnlessSelf(AuthenticatedUser requester, String agentMatricule) {
        if (requester.username().equals(agentMatricule)) return;
        requireQaOrAdmin(requester);
    }
}

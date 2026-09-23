package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.AssignedTaskRequest;
import com.ecobank.rccportal.dto.AssignedTaskResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AssignedTaskService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Notes / tâches — onglet "Notes" de Workflow (ouvert à tous) + programmation par la QA (onglet QA). */
@RestController
@RequestMapping("/api/workflow/tasks")
public class AssignedTaskController {

    private final AssignedTaskService assignedTaskService;

    public AssignedTaskController(AssignedTaskService assignedTaskService) {
        this.assignedTaskService = assignedTaskService;
    }

    @GetMapping("/mine")
    public List<AssignedTaskResponse> mine(@AuthenticationPrincipal AuthenticatedUser requester) {
        return assignedTaskService.myTasks(requester.username());
    }

    @PostMapping
    public AssignedTaskResponse create(@RequestBody AssignedTaskRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        return assignedTaskService.create(requester.username(), request, canAssignOthers(requester));
    }

    /** Team Leader marque le retard/absence d'un agent de son équipe, justifié ou non — voir AssignedTaskService. */
    @PostMapping("/attendance-follow-up")
    public AssignedTaskResponse createAttendanceFollowUp(@RequestBody com.ecobank.rccportal.dto.AttendanceFollowUpRequest request,
                                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrQaOrAdmin(requester);
        return assignedTaskService.createAttendanceFollowUp(requester.username(), request);
    }

    /** Team Leader programme un entretien qualité avec un agent, à faire signer. */
    @PostMapping("/qa-coaching")
    public AssignedTaskResponse createQaCoaching(@RequestBody com.ecobank.rccportal.dto.QaCoachingRequest request,
                                                  @AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrQaOrAdmin(requester);
        return assignedTaskService.createQaCoaching(requester.username(), request);
    }

    /** Vue de contrôle — RH, Superviseur ou Admin uniquement. Toutes équipes confondues. */
    @GetMapping("/oversight")
    public List<AssignedTaskResponse> oversight(@RequestParam(required = false) String category,
                                                 @AuthenticationPrincipal AuthenticatedUser requester) {
        requireRhOrSupervisorOrAdmin(requester);
        return assignedTaskService.oversightTasks(category);
    }

    private void requireTeamLeaderOrQaOrAdmin(AuthenticatedUser requester) {
        if (canAssignOthers(requester)) return;
        throw com.ecobank.rccportal.util.ApiException.forbidden("Réservé au Team Leader, à QA ou à l'admin.");
    }

    private void requireRhOrSupervisorOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        if (!isAdmin && !isRh && !isSupervisor) {
            throw com.ecobank.rccportal.util.ApiException.forbidden("Réservé à RH, au Superviseur ou à l'admin.");
        }
    }

    @PostMapping("/{id}/toggle-done")
    public AssignedTaskResponse toggleDone(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return assignedTaskService.markDone(id, requester.username());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        assignedTaskService.delete(id, requester.username());
    }

    private boolean canAssignOthers(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        boolean isTeamLeader = "team_leader".equalsIgnoreCase(requester.role());
        return isAdmin || isQa || isTeamLeader;
    }
}

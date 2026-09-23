package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.CreateWorkflowRequestRequest;
import com.ecobank.rccportal.dto.WorkflowDecisionRequest;
import com.ecobank.rccportal.dto.WorkflowRequestResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.WorkflowService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Demandes soumises à approbation (congé/absence, changement de procédure,
 * matériel/accès), assignées par le demandeur à une équipe (QA ou ADMIN).
 * Un agent gère ses propres demandes ; seule l'équipe assignée décide.
 */
@RestController
@RequestMapping("/api/workflow/requests")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /** Référentiel RCC360 (motifs de réclamation client → équipe/niveau/SLA/priorité) — pour alimenter
     *  les listes déroulantes de création de demande et de seuils SLA personnalisés. Lecture ouverte
     *  à tout utilisateur connecté, il n'y a rien de sensible dans ce référentiel statique. */
    @GetMapping("/motif-catalog")
    public List<com.ecobank.rccportal.config.RccMotifCatalog.MotifEntry> motifCatalog() {
        return com.ecobank.rccportal.config.RccMotifCatalog.ENTRIES;
    }

    @PostMapping
    public WorkflowRequestResponse submit(
            @RequestBody CreateWorkflowRequestRequest request,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        return workflowService.submit(requester.username(), request);
    }

    /** Mes propres demandes, tous statuts confondus. */
    @GetMapping("/mine")
    public List<WorkflowRequestResponse> mine(@AuthenticationPrincipal AuthenticatedUser requester) {
        return workflowService.listMine(requester.username());
    }

    /** Congés/absences — RH (filtré sur sa propre filiale), Admin/Superviseur/EXCELLIAM (tout),
     *  ou un Team Leader (uniquement les demandes de SA PROPRE équipe menée). */
    @GetMapping("/hr/leave")
    public List<WorkflowRequestResponse> leaveForHr(@AuthenticationPrincipal AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        boolean isExcelliam = "excelliam".equalsIgnoreCase(requester.role());
        boolean isTeamLeader = "team_leader".equalsIgnoreCase(requester.role());
        if (!isAdmin && !isRh && !isSupervisor && !isExcelliam && !isTeamLeader) {
            throw ApiException.forbidden("Only Human Resources, Excelliam, a Team Leader, a Supervisor, or an administrator can view leave requests across the team.");
        }
        if (isTeamLeader) {
            // Un Team Leader ne voit que les demandes de sa propre équipe menée, jamais celles
            // des autres équipes (contrairement à RH/Admin/Superviseur/Excelliam qui voient tout,
            // ou RH qui est filtré par filiale) — voir WorkflowService.listLeaveRequestsForTeamLeader.
            return workflowService.listLeaveRequestsForTeamLeader(requester.username());
        }
        return workflowService.listLeaveRequestsForHr(requester.username(), isAdmin || isSupervisor || isExcelliam);
    }

    /** Team Leader destinataire des demandes d'aide de l'agent connecté (affiché dans le formulaire). */
    @GetMapping("/my-team-leader")
    public java.util.Map<String, String> myTeamLeader(@AuthenticationPrincipal AuthenticatedUser requester) {
        return workflowService.myTeamLeader(requester.username());
    }

    /** Portail Superviseur : demandes d'aide escaladées (sans résolution après le délai). */
    @GetMapping("/escalated")
    public List<WorkflowRequestResponse> escalated(@AuthenticationPrincipal AuthenticatedUser requester) {
        String team = requireReviewerTeam(requester);
        if (!"SUPERVISOR".equals(team) && !"ADMIN".equals(team)) {
            throw ApiException.forbidden("Réservé au Superviseur et à l'administration.");
        }
        return workflowService.listEscalated();
    }

    /** Prise en charge d'une demande d'aide par son Team Leader (ou le supérieur si escaladée). */
    @PostMapping("/{id}/acknowledge")
    public WorkflowRequestResponse acknowledge(@PathVariable Integer id,
                                               @RequestBody(required = false) WorkflowDecisionRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        String team = requireReviewerTeam(requester);
        return workflowService.acknowledge(id, requester.username(), team, request != null ? request.comment() : null);
    }

    /** File d'attente à traiter — équipe QA/ADMIN classique, ou file personnelle d'un Team Leader. */
    @GetMapping("/pending")
    public List<WorkflowRequestResponse> pending(@AuthenticationPrincipal AuthenticatedUser requester) {
        if ("team_leader".equalsIgnoreCase(requester != null ? requester.role() : null)) {
            return workflowService.listPendingForAssignee(requester.username());
        }
        if ("supervisor".equalsIgnoreCase(requester != null ? requester.role() : null)) {
            return workflowService.listEscalated().stream().filter(r -> "PENDING".equals(r.status())).toList();
        }
        return workflowService.listPendingForTeam(requireReviewerTeam(requester));
    }

    /** Historique complet — équipe QA/ADMIN classique, ou historique personnel d'un Team Leader. */
    @GetMapping
    public List<WorkflowRequestResponse> all(@AuthenticationPrincipal AuthenticatedUser requester) {
        if ("team_leader".equalsIgnoreCase(requester != null ? requester.role() : null)) {
            return workflowService.listAllForAssignee(requester.username());
        }
        return workflowService.listAllForTeam(requireReviewerTeam(requester));
    }

    /** Aperçu SLA — stats agrégées ouvertes à tous (transparence sur la lenteur d'une équipe) ;
     *  la liste détaillée des demandes en retard reste réservée à la QA/Admin. */
    @GetMapping("/sla-overview")
    public com.ecobank.rccportal.dto.SlaOverviewResponse slaOverview(@AuthenticationPrincipal AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        return workflowService.getSlaOverview(isAdmin || isQa);
    }

    @PostMapping("/sla-threshold")
    public void setSlaThreshold(@RequestBody java.util.Map<String, Integer> body,
                                 @AuthenticationPrincipal AuthenticatedUser requester) {
        requireReviewerTeam(requester);
        Integer hours = body.get("hours");
        if (hours == null) throw ApiException.badRequest("hours is required.");
        workflowService.setSlaThresholdHours(hours);
    }

    /** Seuils SLA personnalisés par équipe + type — visibles et modifiables par QA/Admin. */
    @GetMapping("/sla-targets")
    public List<com.ecobank.rccportal.dto.SlaTargetResponse> listSlaTargets(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireReviewerTeam(requester);
        return workflowService.listSlaTargets();
    }

    @PostMapping("/sla-targets")
    public com.ecobank.rccportal.dto.SlaTargetResponse upsertSlaTarget(@RequestBody com.ecobank.rccportal.dto.SlaTargetRequest request,
                                                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireReviewerTeam(requester);
        return workflowService.upsertSlaTarget(request, requester != null ? requester.username() : null);
    }

    @DeleteMapping("/sla-targets/{id}")
    public void deleteSlaTarget(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireReviewerTeam(requester);
        workflowService.deleteSlaTarget(id);
    }

    @PostMapping("/{id}/approve")
    public WorkflowRequestResponse approve(
            @PathVariable Integer id,
            @RequestBody(required = false) WorkflowDecisionRequest request,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        String team = requireReviewerTeam(requester);
        String comment = request != null ? request.comment() : null;
        return workflowService.decide(id, requester.username(), team, true, comment);
    }

    @PostMapping("/{id}/reject")
    public WorkflowRequestResponse reject(
            @PathVariable Integer id,
            @RequestBody(required = false) WorkflowDecisionRequest request,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        String team = requireReviewerTeam(requester);
        String comment = request != null ? request.comment() : null;
        return workflowService.decide(id, requester.username(), team, false, comment);
    }

    /** Retire une demande — le demandeur peut supprimer la sienne, l'équipe assignée (QA/Admin) n'importe laquelle. */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        String reviewerTeam = null;
        try {
            reviewerTeam = requireReviewerTeam(requester);
        } catch (ApiException ignored) {
            // pas QA/Admin — reste autorisé à supprimer sa propre demande, voir WorkflowService.delete
        }
        workflowService.delete(id, requester.username(), reviewerTeam);
    }

    /**
     * Détermine l'équipe (QA, ADMIN, ou TEAM_LEADER) du demandeur — même normalisation du
     * champ "service" que UserController (stocké en base sous forme de code, ex.
     * "QUALITY_ASSURANCE", pas comme libellé d'affichage). Pour TEAM_LEADER, le contrôle fin
     * (est-ce BIEN le Team Leader assigné à CETTE demande) est fait dans WorkflowService.decide().
     */
    private String requireReviewerTeam(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        if (isAdmin) return "ADMIN";

        boolean isTeamLeader = requester != null && "team_leader".equalsIgnoreCase(requester.role());
        if (isTeamLeader) return "TEAM_LEADER";

        boolean isSupervisor = requester != null && "supervisor".equalsIgnoreCase(requester.role());
        if (isSupervisor) return "SUPERVISOR";

        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (isQa) return "QA";

        throw ApiException.forbidden("Only Quality Assurance, a Team Leader, or an administrator can review workflow requests.");
    }
}
package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.TrainingService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API de la Formation programmable : planification (jour/semaine/mois),
 * lecons avec parcours (scroll + video plafonnee), et statistiques QA
 * (equipe / individu) sur le taux de suivi reel de A a Z.
 */
@RestController
@RequestMapping("/api/training")
public class TrainingApiController {

    private final TrainingService trainingService;
    private final UserRepository userRepository;

    public TrainingApiController(TrainingService trainingService, UserRepository userRepository) {
        this.trainingService = trainingService;
        this.userRepository = userRepository;
    }

    // ---------- Formations ----------

    @GetMapping("/formations")
    public List<TrainingFormationResponse> listFormations(@AuthenticationPrincipal AuthenticatedUser requester) {
        Long userId = resolveUserId(requester);
        String team = resolveTeam(requester);
        return trainingService.listFormations(userId, team);
    }

    @PostMapping("/formations")
    @ResponseStatus(HttpStatus.CREATED)
    public TrainingFormationResponse createFormation(@RequestBody TrainingFormationRequest request,
                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return trainingService.createFormation(request, resolveUserId(requester));
    }

    @PutMapping("/formations/{id}")
    public TrainingFormationResponse updateFormation(@PathVariable Integer id, @RequestBody TrainingFormationRequest request,
                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return trainingService.updateFormation(id, request, resolveUserId(requester));
    }

    @DeleteMapping("/formations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFormation(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        trainingService.deleteFormation(id);
    }

    // ---------- Lecons ----------

    @GetMapping("/formations/{formationId}/lessons")
    public List<TrainingLessonResponse> listLessons(@PathVariable Integer formationId,
                                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        return trainingService.listLessons(formationId, resolveUserId(requester));
    }

    @GetMapping("/lessons/{lessonId}")
    public TrainingLessonResponse getLesson(@PathVariable Integer lessonId,
                                             @AuthenticationPrincipal AuthenticatedUser requester) {
        return trainingService.getLesson(lessonId, resolveUserId(requester));
    }

    @PostMapping("/formations/{formationId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public TrainingLessonResponse createLesson(@PathVariable Integer formationId, @RequestBody TrainingLessonRequest request,
                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return trainingService.createLesson(formationId, request, resolveUserId(requester));
    }

    @PutMapping("/lessons/{lessonId}")
    public TrainingLessonResponse updateLesson(@PathVariable Integer lessonId, @RequestBody TrainingLessonRequest request,
                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return trainingService.updateLesson(lessonId, request, resolveUserId(requester));
    }

    @DeleteMapping("/lessons/{lessonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLesson(@PathVariable Integer lessonId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        trainingService.deleteLesson(lessonId);
    }

    // ---------- Progression (appele par le lecteur de lecon cote agent) ----------

    @GetMapping("/lessons/{lessonId}/progress")
    public TrainingProgressResponse getProgress(@PathVariable Integer lessonId,
                                                 @AuthenticationPrincipal AuthenticatedUser requester) {
        return trainingService.getProgress(lessonId, requireUserId(requester));
    }

    @PostMapping("/lessons/{lessonId}/progress")
    public TrainingProgressResponse updateProgress(@PathVariable Integer lessonId,
                                                     @RequestBody TrainingProgressUpdateRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser requester) {
        return trainingService.updateProgress(lessonId, requireUserId(requester), request);
    }

    // ---------- Statistiques QA ----------

    @GetMapping("/formations/{formationId}/stats/teams")
    public List<TeamTrainingStatsResponse> teamStats(@PathVariable Integer formationId,
                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return trainingService.getTeamStats(formationId);
    }

    @GetMapping("/formations/{formationId}/stats/agents")
    public List<AgentTrainingStatsResponse> agentStats(@PathVariable Integer formationId,
                                                         @RequestParam(required = false) String team,
                                                         @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return trainingService.getAgentStats(formationId, team);
    }

    // ---------- Helpers ----------

    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && java.util.Set.of("quality assurance", "superviseur qa", "formateur").contains(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Seule la Quality Assurance ou un administrateur peut gerer les formations.");
        }
    }

    private Long requireUserId(AuthenticatedUser requester) {
        Long id = resolveUserId(requester);
        if (id == null) {
            throw ApiException.unauthorized("Utilisateur non authentifie.");
        }
        return id;
    }

    private Long resolveUserId(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) return null;
        return userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .map(User::getId)
                .orElse(null);
    }

    private String resolveTeam(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) return null;
        return userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .map(User::getActivity)
                .orElse(null);
    }
}

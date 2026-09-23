package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.CompetitionRequest;
import com.ecobank.rccportal.dto.CompetitionResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.GameCompetitionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/competitions")
public class GameCompetitionController {

    private final GameCompetitionService competitionService;
    private final com.ecobank.rccportal.repository.UserRepository userRepository;

    public GameCompetitionController(GameCompetitionService competitionService,
                                     com.ecobank.rccportal.repository.UserRepository userRepository) {
        this.competitionService = competitionService;
        this.userRepository = userRepository;
    }

    /** Créée par le formateur (QA/Admin). */
    @PostMapping
    public CompetitionResponse create(@RequestBody CompetitionRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        return competitionService.create(requester, request);
    }

    /** Toutes les compétitions — vue formateur. */
    @GetMapping
    public List<CompetitionResponse> listAll(@AuthenticationPrincipal AuthenticatedUser requester) {
        return competitionService.listAll(requester);
    }

    /** Compétitions où l'équipe du Team Leader connecté est engagée. */
    @GetMapping("/team-leader")
    public List<CompetitionResponse> listForTeamLeader(@AuthenticationPrincipal AuthenticatedUser requester) {
        return competitionService.listForTeamLeader(requester);
    }

    @GetMapping("/{id}")
    public CompetitionResponse getOne(@PathVariable Integer id) {
        return competitionService.getOne(id);
    }

    /** Le Team Leader choisit et valide les membres de sa propre équipe. */
    @PostMapping("/{id}/validate-members")
    public CompetitionResponse validateMembers(@PathVariable Integer id, @RequestBody Map<String, List<Long>> body,
                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        return competitionService.validateTeamMembers(requester, id, body.getOrDefault("userIds", List.of()));
    }

    /** Le formateur choisit directement les participants — uniquement pour une compétition stagiaires. */
    @PostMapping("/{id}/trainee-participants")
    public CompetitionResponse addTraineeParticipants(@PathVariable Integer id, @RequestBody Map<String, List<Long>> body,
                                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        return competitionService.addTraineeParticipants(requester, id, body.getOrDefault("userIds", List.of()));
    }

    /** Compétitions dans lesquelles l'agent connecté peut jouer maintenant. */
    @GetMapping("/mine")
    public List<CompetitionResponse> myCompetitions(@AuthenticationPrincipal AuthenticatedUser requester) {
        Long userId = resolveUserId(requester);
        if (userId == null) return List.of();
        return competitionService.myActiveCompetitions(userId);
    }

    /** L'agent soumet son score à la fin de sa partie de compétition. */
    @PostMapping("/{id}/complete")
    public void complete(@PathVariable Integer id, @RequestBody Map<String, Integer> body,
                          @AuthenticationPrincipal AuthenticatedUser requester) {
        Long userId = resolveUserId(requester);
        if (userId == null) return;
        competitionService.recordScore(id, userId, body.getOrDefault("score", 0));
    }

    private Long resolveUserId(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) return null;
        return userRepository.findFirstByUsernameIgnoreCase(requester.username()).map(u -> u.getId()).orElse(null);
    }
}

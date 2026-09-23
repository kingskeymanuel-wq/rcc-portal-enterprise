package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.GameService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;
    private final UserRepository userRepository;

    public GameController(GameService gameService, UserRepository userRepository) {
        this.gameService = gameService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<GameDefinitionResponse> list(@AuthenticationPrincipal AuthenticatedUser requester) {
        return gameService.listActive(resolveUserId(requester));
    }

    @GetMapping("/admin")
    public List<GameDefinitionResponse> listAdmin(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return gameService.listAllForAdmin();
    }

    @PutMapping("/admin/{id}")
    public GameDefinitionResponse update(@PathVariable Integer id, @RequestBody GameDefinitionUpdateRequest request,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return gameService.update(id, request);
    }

    @PostMapping("/{gameKey}/score")
    public GameScoreResponse submitScore(@PathVariable String gameKey, @RequestBody GameScoreSubmitRequest request,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        Long userId = resolveUserId(requester);
        if (userId == null) throw ApiException.unauthorized("Utilisateur non authentifié.");
        return gameService.submitScore(gameKey, userId, request);
    }

    @GetMapping("/{gameKey}/leaderboard")
    public List<GameScoreResponse> leaderboard(@PathVariable String gameKey) {
        return gameService.leaderboard(gameKey, 10);
    }

    /** Résultat détaillé d'une tentative d'évaluation QCM — voir games.js resultScreen()/submitEvaluationResult(). */
    @PostMapping("/{gameKey}/evaluation-result")
    public void submitEvaluationResult(@PathVariable String gameKey, @RequestBody GameEvaluationResultRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        Long userId = resolveUserId(requester);
        if (userId == null) throw ApiException.unauthorized("Utilisateur non authentifié.");
        gameService.submitEvaluationResult(gameKey, userId, request);
    }

    /** L'agent a-t-il déjà terminé la session d'évaluation en cours pour ce jeu ? Voir games.js launchGame(). */
    @GetMapping("/{gameKey}/evaluation-status")
    public GameEvaluationStatusResponse evaluationStatus(@PathVariable String gameKey,
                                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        Long userId = resolveUserId(requester);
        if (userId == null) throw ApiException.unauthorized("Utilisateur non authentifié.");
        return gameService.evaluationStatus(gameKey, userId);
    }

    /** Vue de contrôle QA — qui a fait l'évaluation, quel résultat. */
    @GetMapping("/{gameKey}/evaluation-results")
    public List<GameEvaluationAgentResultResponse> evaluationResults(@PathVariable String gameKey,
                                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return gameService.evaluationResultsFor(gameKey);
    }

    /** Débloque une nouvelle tentative pour UN SEUL agent — QA/Admin uniquement. */
    @PostMapping("/{gameKey}/unlock-user/{userId}")
    public void unlockUser(@PathVariable String gameKey, @PathVariable Long userId,
                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        gameService.unlockUser(gameKey, userId, requester != null ? requester.username() : null);
    }

    /** Ouvre une nouvelle session d'évaluation — QA/Admin uniquement, rouvre l'accès à tout le monde. */
    @PostMapping("/admin/{id}/new-evaluation-round")
    public void startNewEvaluationRound(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        gameService.startNewEvaluationRound(id);
    }

    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Seule la Quality Assurance ou un administrateur peut gérer les jeux.");
        }
    }

    private Long resolveUserId(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) return null;
        return userRepository.findFirstByUsernameIgnoreCase(requester.username()).map(User::getId).orElse(null);
    }
}

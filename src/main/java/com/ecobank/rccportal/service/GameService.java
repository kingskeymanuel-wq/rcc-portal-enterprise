package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.GameDefinition;
import com.ecobank.rccportal.model.GameEvaluationAttempt;
import com.ecobank.rccportal.model.GameScore;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.GameDefinitionRepository;
import com.ecobank.rccportal.repository.GameEvaluationAttemptRepository;
import com.ecobank.rccportal.repository.GameScoreRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GameService {

    private final GameDefinitionRepository gameDefinitionRepository;
    private final GameScoreRepository gameScoreRepository;
    private final GameEvaluationAttemptRepository gameEvaluationAttemptRepository;
    private final com.ecobank.rccportal.repository.GameEvaluationUnlockRepository gameEvaluationUnlockRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GameService(GameDefinitionRepository gameDefinitionRepository, GameScoreRepository gameScoreRepository,
                        GameEvaluationAttemptRepository gameEvaluationAttemptRepository,
                        com.ecobank.rccportal.repository.GameEvaluationUnlockRepository gameEvaluationUnlockRepository,
                        UserRepository userRepository) {
        this.gameDefinitionRepository = gameDefinitionRepository;
        this.gameScoreRepository = gameScoreRepository;
        this.gameEvaluationAttemptRepository = gameEvaluationAttemptRepository;
        this.gameEvaluationUnlockRepository = gameEvaluationUnlockRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<GameDefinitionResponse> listActive(Long currentUserId) {
        try {
            String myTeam = null;
            if (currentUserId != null) {
                User me = userRepository.findById(currentUserId).orElse(null);
                if (me != null) myTeam = com.ecobank.rccportal.util.TeamClassifier.classify(me.getActivity()).name();
            }
            String effectiveTeam = myTeam;
            return gameDefinitionRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                    .filter(g -> g.getTargetTeam() == null || g.getTargetTeam().isBlank() || g.getTargetTeam().equalsIgnoreCase(effectiveTeam))
                    .map(g -> toResponse(g, currentUserId))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public List<GameDefinitionResponse> listAllForAdmin() {
        return gameDefinitionRepository.findAllByOrderBySortOrderAsc().stream()
                .map(g -> toResponse(g, null))
                .collect(Collectors.toList());
    }

    @Transactional
    public GameDefinitionResponse update(Integer gameId, GameDefinitionUpdateRequest request) {
        GameDefinition g = gameDefinitionRepository.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("Jeu introuvable."));
        if (request.title() != null && !request.title().isBlank()) g.setTitle(request.title().trim());
        if (request.description() != null) g.setDescription(request.description());
        if (request.configJson() != null) g.setConfigJson(request.configJson());
        if (request.active() != null) g.setActive(request.active());
        if (request.targetTeam() != null) g.setTargetTeam(request.targetTeam().isBlank() ? null : request.targetTeam().trim().toUpperCase());
        return toResponse(gameDefinitionRepository.save(g), null);
    }

    @Transactional
    public GameScoreResponse submitScore(String gameKey, Long userId, GameScoreSubmitRequest request) {
        if (gameDefinitionRepository.findByGameKey(gameKey).isEmpty()) {
            throw ApiException.notFound("Jeu inconnu : " + gameKey);
        }
        GameScore score = GameScore.builder()
                .gameKey(gameKey)
                .userId(userId)
                .score(request.score() == null ? 0 : request.score())
                .correctCount(request.correctCount())
                .totalCount(request.totalCount())
                .build();
        score = gameScoreRepository.save(score);
        User u = userRepository.findById(userId).orElse(null);
        return new GameScoreResponse(u != null ? (u.getName() != null ? u.getName() : u.getUsername()) : "—",
                score.getScore(), score.getCorrectCount(), score.getTotalCount(), score.getPlayedAt());
    }

    @Transactional(readOnly = true)
    public List<GameScoreResponse> leaderboard(String gameKey, int limit) {
        List<GameScore> scores = gameScoreRepository.findByGameKeyOrderByScoreDesc(gameKey);
        return scores.stream()
                .sorted(Comparator.comparing(GameScore::getScore).reversed())
                .limit(limit)
                .map(s -> {
                    User u = userRepository.findById(s.getUserId()).orElse(null);
                    String name = u != null ? (u.getName() != null ? u.getName() : u.getUsername()) : "—";
                    return new GameScoreResponse(name, s.getScore(), s.getCorrectCount(), s.getTotalCount(), s.getPlayedAt());
                })
                .collect(Collectors.toList());
    }

    /**
     * Enregistre une tentative d'évaluation QCM (1re ou 2e du cycle) — voir games.js
     * resultScreen(). Ne lève jamais d'erreur bloquante : un échec ici ne doit jamais
     * empêcher l'agent de voir son résultat à l'écran (déjà affiché côté client avant
     * cet appel, best-effort côté serveur — voir catch silencieux dans games.js).
     */
    @Transactional
    public void submitEvaluationResult(String gameKey, Long userId, GameEvaluationResultRequest request) {
        String answersJson;
        try {
            answersJson = objectMapper.writeValueAsString(request.answers());
        } catch (Exception e) {
            answersJson = null;
        }
        Integer currentRound = gameDefinitionRepository.findByGameKey(gameKey)
                .map(GameDefinition::getEvaluationRound).orElse(1);
        GameEvaluationAttempt attempt = GameEvaluationAttempt.builder()
                .gameKey(gameKey)
                .userId(userId)
                .attemptNumber(request.attemptNumber() == null ? 1 : request.attemptNumber())
                .evaluationRound(currentRound)
                .score(request.score() == null ? 0 : request.score())
                .correctCount(request.correctCount())
                .totalCount(request.totalCount())
                .answersJson(answersJson)
                .build();
        gameEvaluationAttemptRepository.save(attempt);
    }

    /**
     * Est-ce que cet utilisateur a déjà terminé les 2 tentatives de la session d'évaluation
     * EN COURS pour ce jeu ? Si oui, renvoie son résultat final (pour réaffichage direct,
     * sans permettre de rejouer — voir games.js launchGame()). Deux façons de rouvrir
     * l'accès : (1) QA ouvre une nouvelle session pour TOUT LE MONDE
     * (startNewEvaluationRound(), incrémente EvaluationRound) ; (2) QA débloque UN SEUL agent
     * (unlockUser()) — dans ce cas la dernière tentative complète est ignorée si un
     * déblocage a été accordé après elle, sans toucher aux autres agents.
     */
    @Transactional(readOnly = true)
    public GameEvaluationStatusResponse evaluationStatus(String gameKey, Long userId) {
        Integer currentRound = gameDefinitionRepository.findByGameKey(gameKey)
                .map(GameDefinition::getEvaluationRound).orElse(1);
        List<GameEvaluationAttempt> attempts = gameEvaluationAttemptRepository
                .findByGameKeyAndUserIdAndEvaluationRoundOrderByAttemptNumberDesc(gameKey, userId, currentRound);
        GameEvaluationAttempt last = attempts.stream().findFirst().orElse(null);
        if (last == null || last.getAttemptNumber() == null || last.getAttemptNumber() < 2) {
            return GameEvaluationStatusResponse.notCompleted();
        }

        var latestUnlock = gameEvaluationUnlockRepository.findFirstByGameKeyAndUserIdOrderByCreatedAtDesc(gameKey, userId);
        if (latestUnlock.isPresent() && latestUnlock.get().getCreatedAt().isAfter(last.getCreatedAt())) {
            return GameEvaluationStatusResponse.notCompleted();
        }

        List<GameEvaluationResultRequest.AnswerDetail> answers;
        try {
            answers = last.getAnswersJson() == null ? List.of() : List.of(
                    objectMapper.readValue(last.getAnswersJson(), GameEvaluationResultRequest.AnswerDetail[].class));
        } catch (Exception e) {
            answers = List.of();
        }
        return new GameEvaluationStatusResponse(true, last.getScore(), last.getCorrectCount(), last.getTotalCount(), answers);
    }

    /**
     * Vue de contrôle QA — pour chaque agent, son dernier statut face à cette évaluation
     * (en cours, terminé avec score, ou jamais commencé — absent de la liste). Voir
     * games.html "Résultats des évaluations".
     */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.GameEvaluationAgentResultResponse> evaluationResultsFor(String gameKey) {
        java.util.Map<Long, GameEvaluationAttempt> latestByUser = new java.util.LinkedHashMap<>();
        for (GameEvaluationAttempt a : gameEvaluationAttemptRepository.findByGameKeyOrderByCreatedAtDesc(gameKey)) {
            latestByUser.putIfAbsent(a.getUserId(), a); // déjà trié par CreatedAt desc — le premier vu par agent est le plus récent
        }

        List<com.ecobank.rccportal.dto.GameEvaluationAgentResultResponse> results = new java.util.ArrayList<>();
        for (var entry : latestByUser.entrySet()) {
            GameEvaluationAttempt a = entry.getValue();
            User u = userRepository.findById(entry.getKey()).orElse(null);
            boolean completed = a.getAttemptNumber() != null && a.getAttemptNumber() >= 2;
            var latestUnlock = gameEvaluationUnlockRepository.findFirstByGameKeyAndUserIdOrderByCreatedAtDesc(gameKey, entry.getKey());
            if (completed && latestUnlock.isPresent() && latestUnlock.get().getCreatedAt().isAfter(a.getCreatedAt())) {
                completed = false; // débloqué depuis — plus vraiment "terminé" tant qu'il n'a pas rejoué
            }
            results.add(new com.ecobank.rccportal.dto.GameEvaluationAgentResultResponse(
                    entry.getKey(),
                    u != null ? u.getUsername() : "—",
                    u != null ? (u.getName() != null ? u.getName() : u.getUsername()) : "Agent supprimé",
                    u != null ? com.ecobank.rccportal.util.TeamClassifier.classify(u.getActivity()).name() : null,
                    completed,
                    a.getAttemptNumber(),
                    a.getScore(),
                    a.getCorrectCount(),
                    a.getTotalCount(),
                    a.getCreatedAt()
            ));
        }
        results.sort(java.util.Comparator.comparing(com.ecobank.rccportal.dto.GameEvaluationAgentResultResponse::fullName,
                String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    /** Débloque une nouvelle tentative pour UN SEUL agent, sans affecter les autres — voir evaluationStatus(). */
    @Transactional
    public void unlockUser(String gameKey, Long userId, String unlockedByUsername) {
        gameEvaluationUnlockRepository.save(com.ecobank.rccportal.model.GameEvaluationUnlock.builder()
                .gameKey(gameKey)
                .userId(userId)
                .unlockedByUsername(unlockedByUsername)
                .build());
    }

    /** Ouvre une nouvelle session d'évaluation pour ce jeu — QA/Admin uniquement (voir
     *  GameController). Tout le monde peut repasser l'évaluation, même ceux qui avaient déjà
     *  terminé la précédente. */
    @Transactional
    public void startNewEvaluationRound(Integer gameId) {
        GameDefinition g = gameDefinitionRepository.findById(gameId)
                .orElseThrow(() -> ApiException.notFound("Jeu introuvable."));
        g.setEvaluationRound((g.getEvaluationRound() == null ? 1 : g.getEvaluationRound()) + 1);
        gameDefinitionRepository.save(g);
    }

    private GameDefinitionResponse toResponse(GameDefinition g, Long currentUserId) {
        Integer best = null;
        if (currentUserId != null) {
            best = gameScoreRepository.findByGameKeyAndUserIdOrderByScoreDesc(g.getGameKey(), currentUserId)
                    .stream().map(GameScore::getScore).max(Integer::compareTo).orElse(null);
        }
        return new GameDefinitionResponse(g.getGameId(), g.getGameKey(), g.getMechanic(), g.getTitle(), g.getDescription(),
                g.getIcon(), g.getColorFrom(), g.getColorTo(), g.getConfigJson(), g.getSortOrder(), g.getActive(), best, g.getTargetTeam());
    }
}

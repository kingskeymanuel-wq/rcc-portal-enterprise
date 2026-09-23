package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.GameEvaluationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameEvaluationAttemptRepository extends JpaRepository<GameEvaluationAttempt, Integer> {

    List<GameEvaluationAttempt> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Tentative la plus récente d'un utilisateur, tous jeux confondus — utilisée par le Reporting. */
    Optional<GameEvaluationAttempt> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    /** Tentatives d'un utilisateur pour un jeu et une session d'évaluation précis — voir
     *  GameService.evaluationStatus() : la dernière (attemptNumber le plus élevé) détermine
     *  si l'agent a déjà terminé cette session. */
    List<GameEvaluationAttempt> findByGameKeyAndUserIdAndEvaluationRoundOrderByAttemptNumberDesc(
            String gameKey, Long userId, Integer evaluationRound);

    /** Toutes les tentatives d'un jeu, plus récentes en premier — utilisé par la vue de contrôle QA (GameService.evaluationResultsFor). */
    List<GameEvaluationAttempt> findByGameKeyOrderByCreatedAtDesc(String gameKey);
}

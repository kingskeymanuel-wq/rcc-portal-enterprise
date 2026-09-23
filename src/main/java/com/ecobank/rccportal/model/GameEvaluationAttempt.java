package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Une tentative d'évaluation QCM (jeux à choix — Quiz Éclair, Qui Veut Gagner des Millions,
 * Vrai/Faux Chrono, Roue de la Fortune, Duel Chrono, Chrono Challenge).
 *
 * L'agent a droit à 2 tentatives par cycle : la 1re ne révèle aucune correction (voir
 * games.js — renderFirstAttemptResult), seule la 2e affiche le détail vert/rouge. Les deux
 * sont enregistrées ici ; c'est la plus RÉCENTE (peu importe son numéro) qui remonte dans le
 * Reporting — voir ReportingService.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "GameEvaluationAttempts", schema = "dbo")
public class GameEvaluationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AttemptId")
    private Integer attemptId;

    @Column(name = "GameKey", nullable = false, length = 50)
    private String gameKey;

    @Column(name = "UserId", nullable = false)
    private Long userId;

    @Column(name = "AttemptNumber", nullable = false)
    private Integer attemptNumber;

    /** Copie de GameDefinition.EvaluationRound au moment de la tentative — voir GameService.evaluationStatus(). */
    @Column(name = "EvaluationRound", nullable = false)
    @Builder.Default
    private Integer evaluationRound = 1;

    @Column(name = "Score", nullable = false)
    private Integer score;

    @Column(name = "CorrectCount")
    private Integer correctCount;

    @Column(name = "TotalCount")
    private Integer totalCount;

    /** JSON brut du détail des réponses (question/options/index choisi/index correct) — pour audit QA, jamais réaffiché tel quel côté agent. */
    @Column(name = "AnswersJson", columnDefinition = "NVARCHAR(MAX)")
    private String answersJson;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

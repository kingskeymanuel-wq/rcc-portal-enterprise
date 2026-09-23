package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Déblocage individuel d'une évaluation QCM — accordé par QA/Admin à UN agent précis, sans
 * affecter les autres (contrairement à GameDefinition.evaluationRound, qui rouvre l'évaluation
 * à toute l'équipe — voir GameService.startNewEvaluationRound()). Voir
 * GameService.evaluationStatus() : un agent est considéré "pas encore terminé" si un
 * déblocage a été accordé APRÈS sa dernière tentative complète (2e tentative).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "GameEvaluationUnlocks", schema = "dbo")
public class GameEvaluationUnlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UnlockId")
    private Integer unlockId;

    @Column(name = "GameKey", nullable = false, length = 50)
    private String gameKey;

    @Column(name = "UserId", nullable = false)
    private Long userId;

    @Column(name = "UnlockedByUsername", length = 100)
    private String unlockedByUsername;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

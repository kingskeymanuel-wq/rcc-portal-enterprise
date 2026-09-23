package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Suivi réel (côté serveur, non falsifiable par le client) du parcours d'un
 * agent sur une leçon : % de défilement du texte (scroll) + % de la vidéo
 * réellement visionnée. Les deux valeurs ne peuvent que progresser (jamais
 * redescendre) — voir TrainingService.updateProgress().
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "TrainingProgress", schema = "dbo",
       uniqueConstraints = @UniqueConstraint(name = "UQ_TrainingProgress_Lesson_User", columnNames = {"LessonId", "UserId"}))
public class TrainingProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ProgressId")
    private Integer progressId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LessonId", nullable = false)
    private TrainingLesson lesson;

    @Column(name = "UserId", nullable = false)
    private Long userId;

    /** 0-100 : plus haut point de défilement atteint dans le contenu texte. */
    @Column(name = "ScrollPercent", nullable = false)
    @Builder.Default
    private Integer scrollPercent = 0;

    /** 0-100 : portion de la vidéo effectivement visionnée (à vitesse <= max autorisée). */
    @Column(name = "VideoWatchedPercent", nullable = false)
    @Builder.Default
    private Integer videoWatchedPercent = 0;

    @Column(name = "Completed", nullable = false)
    @Builder.Default
    private Boolean completed = false;

    @Column(name = "StartedAt")
    private LocalDateTime startedAt;

    @Column(name = "CompletedAt")
    private LocalDateTime completedAt;

    @Column(name = "LastActivityAt")
    private LocalDateTime lastActivityAt;

    /** Nombre de tentatives de dépassement de vitesse vidéo détectées (audit anti-triche). */
    @Column(name = "SpeedViolationCount", nullable = false)
    @Builder.Default
    private Integer speedViolationCount = 0;
}

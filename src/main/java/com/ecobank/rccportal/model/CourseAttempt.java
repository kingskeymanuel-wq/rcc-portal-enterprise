package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * La progression d'un agent sur un cours. AnswersJson stocke les réponses
 * données (index choisi par question pour STANDARD, valeur 1-5 pour
 * SELF_ASSESSMENT) sous forme de document JSON simple — évite une table
 * supplémentaire vu le volume raisonnable de réponses par tentative.
 *
 * Une seule ligne par couple (Course, User) — attemptNumber (1 ou 2) et
 * finalized (irréversible une fois vrai) portent l'évolution entre la
 * première tentative et le rattrapage éventuel, plutôt que multiplier les
 * lignes : plus simple à interroger pour l'historique QA/agent.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "CourseAttempts", schema = "dbo",
        uniqueConstraints = @UniqueConstraint(columnNames = {"CourseId", "UserId"}))
public class CourseAttempt extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AttemptId")
    private Integer attemptId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CourseId", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    /** "TODO" | "IN_PROGRESS" | "DONE" */
    @Column(name = "Status", nullable = false, length = 20)
    private String status;

    /** Score en % pour un cours STANDARD ; null pour un SELF_ASSESSMENT (pas de notion de score). */
    @Column(name = "Score")
    private Integer score;

    @Column(name = "AnswersJson", length = 2000)
    private String answersJson;

    @Column(name = "CompletedAt")
    private LocalDateTime completedAt;

    /** 1 = première tentative, 2 = rattrapage (le maximum autorisé). */
    @Column(name = "AttemptNumber", nullable = false)
    private Integer attemptNumber;

    /** Une fois vrai, plus aucune nouvelle tentative n'est possible (réussite au 1er essai, ou 2e essai consommé). */
    @Column(name = "Finalized", nullable = false)
    private Boolean finalized;

    /** Pourcentage de la vidéo effectivement visionné (0-100) — un cours avec vidéo n'est
     *  jamais marqué DONE avant que ce champ atteigne 100, quel que soit le reste du cours. */
    @Column(name = "VideoWatchedPercent", nullable = false)
    @Builder.Default
    private Integer videoWatchedPercent = 0;

    /** Nombre de tentatives d'avance rapide/retour en arrière détectées côté lecteur —
     *  jamais bloquant en soi (le agent peut retenter), mais tracé pour QA/audit. */
    @Column(name = "SeekViolationCount", nullable = false)
    @Builder.Default
    private Integer seekViolationCount = 0;
}
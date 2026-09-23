package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "QualityEvaluations", schema = "dbo")
public class QualityEvaluation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "EvaluationId")
    private Integer evaluationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "AgentUserId", nullable = false)
    private User agent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "EvaluatorUserId")
    private User evaluator;

    @Column(name = "EvaluationDate", nullable = false)
    private LocalDate evaluationDate;

    @Column(name = "CallDate", nullable = false)
    private LocalDate callDate;

    @Column(name = "RecordingRef", length = 50)
    private String recordingRef;

    @Column(name = "DurationMinutes")
    private Integer durationMinutes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "MotifId")
    private QualityMotif motif;

    @Column(name = "Strengths", length = 500)
    private String strengths;

    @Column(name = "Improvements", length = 500)
    private String improvements;

    @Column(name = "Comment", length = 1000)
    private String comment;

    /** 'pending' | 'done' */
    @Column(name = "FeedbackStatus", nullable = false, length = 20)
    private String feedbackStatus;

    @Column(name = "FeedbackDate")
    private LocalDate feedbackDate;

    /** VOICE | CHAT — canal de l'interaction évaluée, détermine quels critères de la grille
     *  s'appliquent (voir QualityCriterion.channel). Non nul dès la création — voir
     *  QualityEvaluationService.assertValid(). */
    @Column(name = "Channel", nullable = false, length = 10)
    private String channel;
}

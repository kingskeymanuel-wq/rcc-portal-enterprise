package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "QualityEvaluationScores", schema = "dbo",
       uniqueConstraints = @UniqueConstraint(columnNames = {"EvaluationId", "CriterionId"}))
public class QualityEvaluationScore extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "EvaluationScoreId")
    private Integer evaluationScoreId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "EvaluationId", nullable = false)
    private QualityEvaluation evaluation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CriterionId", nullable = false)
    private QualityCriterion criterion;

    /** 0, 1 ou 2 ; null si non applicable. */
    @Column(name = "ScoreValue")
    private Short scoreValue;

    @Column(name = "IsNotApplicable", nullable = false)
    private Boolean isNotApplicable;
}

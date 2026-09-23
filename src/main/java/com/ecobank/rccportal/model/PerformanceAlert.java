package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Alerte de performance détectée automatiquement par AlertEngineService — équivalent du
 * "Alert Engine" décrit par l'utilisateur, mais adapté aux métriques réellement suivies
 * dans ce portail (SCORE_QA, SCORE_EVALUATION, présence, absences, dépassements de pause)
 * plutôt qu'aux métriques génériques d'un centre d'appel classique (AHT/CSAT/FCR, non
 * suivies ici). Générées chaque nuit par PerformanceAlertScheduler, ou à la demande via
 * DataAnalysisController.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "PerformanceAlerts", schema = "dbo")
public class PerformanceAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AlertId")
    private Integer alertId;

    @Column(name = "UserId", nullable = false)
    private Long userId;

    /** ex. "2026-08" — mois auquel se rapporte l'alerte. */
    @Column(name = "PeriodMonth", nullable = false, length = 7)
    private String periodMonth;

    /** PRESENCE_FAIBLE | ABSENTEISME_ELEVE | PAUSES_FREQUENTES | SCORE_QA_FAIBLE | EVALUATION_FAIBLE | PERFORMANCE_EN_BAISSE */
    @Column(name = "AlertType", nullable = false, length = 40)
    private String alertType;

    /** INFO | ATTENTION | CRITIQUE */
    @Column(name = "Severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "Message", nullable = false, length = 500)
    private String message;

    @Column(name = "Acknowledged", nullable = false)
    @Builder.Default
    private Boolean acknowledged = false;

    @Column(name = "AcknowledgedByUsername", length = 100)
    private String acknowledgedByUsername;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

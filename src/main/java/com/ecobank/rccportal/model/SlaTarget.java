package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Seuil d'alerte SLA personnalisé pour une combinaison équipe + type de
 * demande — remplace le seuil global (WorkflowService.DEFAULT_SLA_THRESHOLD_HOURS
 * / SiteSettings) pour cette combinaison précise. S'il n'existe pas de règle
 * pour une combinaison donnée, le seuil global reste utilisé (voir
 * WorkflowService.resolveThresholdFor).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "SlaTargets", schema = "dbo",
       uniqueConstraints = @UniqueConstraint(name = "UQ_SlaTargets_Team_Type", columnNames = {"Team", "Type"}))
public class SlaTarget extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "SlaTargetId")
    private Integer slaTargetId;

    /** "QA" | "ADMIN" */
    @Column(name = "Team", nullable = false, length = 10)
    private String team;

    /** "LEAVE" | "PROCEDURE_CHANGE" | "ACCESS" | "TEAM_ASSIGNMENT" */
    @Column(name = "Type", nullable = false, length = 30)
    private String type;

    @Column(name = "ThresholdHours", nullable = false)
    private Integer thresholdHours;

    @Column(name = "UpdatedByUserId")
    private Long updatedByUserId;
}

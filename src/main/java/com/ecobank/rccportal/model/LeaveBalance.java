package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Allocation annuelle de jours de congé par agent. Le nombre de jours utilisés
 * n'est PAS stocké ici — il est recalculé à la volée depuis les WorkflowRequest
 * de type LEAVE approuvées sur l'année, pour rester toujours cohérent avec la
 * source de vérité (voir LeaveBalanceService).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "LeaveBalances", schema = "dbo",
       uniqueConstraints = @UniqueConstraint(name = "UQ_LeaveBalances_User_Year", columnNames = {"UserId", "Year"}))
public class LeaveBalance extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BalanceId")
    private Integer balanceId;

    @Column(name = "UserId", nullable = false)
    private Long userId;

    @Column(name = "Year", nullable = false)
    private Integer year;

    @Column(name = "AllocatedDays", nullable = false)
    @Builder.Default
    private Integer allocatedDays = 24;

    @Column(name = "UpdatedByUserId")
    private Long updatedByUserId;
}

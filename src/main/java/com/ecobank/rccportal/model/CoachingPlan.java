package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "CoachingPlans", schema = "dbo")
public class CoachingPlan extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CoachingPlanId")
    private Integer coachingPlanId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "AgentUserId", nullable = false)
    private User agent;

    @Column(name = "Axis", nullable = false, length = 300)
    private String axis;

    @Column(name = "DueDate", nullable = false)
    private LocalDate dueDate;

    /** 'todo' | 'in_progress' | 'done' */
    @Column(name = "Status", nullable = false, length = 20)
    private String status;

    @Column(name = "Note", length = 1000)
    private String note;
}

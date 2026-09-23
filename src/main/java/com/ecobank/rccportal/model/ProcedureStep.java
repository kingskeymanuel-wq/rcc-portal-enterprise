package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "ProcedureSteps", schema = "dbo")
public class ProcedureStep extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ProcedureStepId")
    private Integer procedureStepId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ProcedureId", nullable = false)
    private Procedure procedure;

    @Column(name = "StepNumber", nullable = false)
    private Integer stepNumber;

    @Column(name = "Content", nullable = false, length = 1000)
    private String content;
}

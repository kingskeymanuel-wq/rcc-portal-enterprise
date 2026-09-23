package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Une option de réponse pour un nœud (ProcedureWorkflowNode). Mène soit à un
 * nœud suivant (nextNode renseigné), soit à une fin de parcours (outcome
 * renseigné : "FIDELISATION" ou "CLOTURE") — jamais les deux à la fois,
 * vérifié côté service (ProcedureWorkflowService).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "ProcedureWorkflowOptions", schema = "dbo")
public class ProcedureWorkflowOption extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "OptionId")
    private Integer optionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "NodeId", nullable = false)
    private ProcedureWorkflowNode node;

    @Column(name = "Label", nullable = false, length = 200)
    private String label;

    /** Renseigné si cette réponse mène à une autre question. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "NextNodeId")
    private ProcedureWorkflowNode nextNode;

    /** Renseigné si cette réponse termine le parcours : "FIDELISATION" ou "CLOTURE". */
    @Column(name = "Outcome", length = 20)
    private String outcome;
}
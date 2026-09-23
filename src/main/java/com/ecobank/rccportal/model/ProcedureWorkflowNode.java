package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Un nœud = une question posée à l'agent dans le parcours interactif d'une
 * procédure. Remplace l'ancienne liste d'étapes texte (ProcedureStep) — voir
 * ProcedureWorkflowOption pour les réponses possibles et leur branchement.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "ProcedureWorkflowNodes", schema = "dbo")
public class ProcedureWorkflowNode extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "NodeId")
    private Integer nodeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ProcedureId", nullable = false)
    private Procedure procedure;

    @Column(name = "QuestionText", nullable = false, length = 1000)
    private String questionText;

    /** Un seul nœud de départ par procédure — c'est celui affiché en premier à l'agent. */
    @Column(name = "IsStart", nullable = false)
    private Boolean isStart;

    /** "Coup d'œil" base de connaissance — lien optionnel affiché à côté de cette question. */
    @Column(name = "SuggestionLabel", length = 200)
    private String suggestionLabel;

    @Column(name = "SuggestionUrl", length = 500)
    private String suggestionUrl;
}
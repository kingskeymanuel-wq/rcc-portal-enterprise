package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Demande générique à valider (congé/absence, changement de procédure,
 * matériel/accès) — une seule étape d'approbation. La demande porte une
 * période (jour/semaine/mois — periodFrom/periodTo bornent la période réelle,
 * periodType est purement informatif pour l'affichage de la granularité
 * choisie) et est assignée à une équipe (QA ou ADMIN) : seule cette équipe
 * la voit dans sa file à valider et peut la décider.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "WorkflowRequests", schema = "dbo")
public class WorkflowRequest extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RequestId")
    private Integer requestId;

    /** "LEAVE" | "PROCEDURE_CHANGE" | "ACCESS" */
    @Column(name = "Type", nullable = false, length = 30)
    private String type;

    @Column(name = "Title", nullable = false, length = 200)
    private String title;

    @Column(name = "Details", length = 2000)
    private String details;

    /** "DAY" | "WEEK" | "MONTH" — granularité choisie par le demandeur, pour l'affichage. */
    @Column(name = "PeriodType", nullable = false, length = 10)
    private String periodType;

    @Column(name = "PeriodFrom", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "PeriodTo", nullable = false)
    private LocalDate periodTo;

    /** Service concerné — pertinent pour PROCEDURE_CHANGE/ACCESS (pas pour LEAVE, qui concerne l'agent lui-même). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "RelatedServiceId")
    private RccService relatedService;

    /** "QA" | "ADMIN" | "TEAM_LEADER" (congés — voir WorkflowService.submit) | code équipe RCC360 —
     *  équipe à laquelle la demande est assignée pour validation. Pour "TEAM_LEADER", seul le
     *  Team Leader précis porté par assignedTo peut décider, pas n'importe quel Team Leader. */
    @Column(name = "AssignedTeam", nullable = false, length = 10)
    private String assignedTeam;

    /**
     * Responsable désigné (rôle RESPONSABLE) choisi par le demandeur — purement
     * informatif/priorité, n'importe qui dans AssignedTeam garde le droit de
     * décider (voir WorkflowService.decide).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "AssignedToUserId")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "RequestedByUserId", nullable = false)
    private User requestedBy;

    /** "PENDING" | "APPROVED" | "REJECTED" */
    @Column(name = "Status", nullable = false, length = 20)
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "DecidedByUserId")
    private User decidedBy;

    @Column(name = "DecisionComment", length = 500)
    private String decisionComment;

    @Column(name = "DecidedAt")
    private LocalDateTime decidedAt;

    // ── Demandes « faciliter mon travail » (accès, outils, matériel, difficulté) ──

    /** NORMAL | URGENT | BLOQUANT — urgence ressentie par l'agent. */
    @Column(name = "Priority", length = 20)
    private String priority;

    /** Le Team Leader (ou le supérieur) a pris la demande en charge — la demande reste ouverte. */
    @Column(name = "AcknowledgedAt")
    private LocalDateTime acknowledgedAt;

    @Column(name = "AcknowledgedBy", length = 150)
    private String acknowledgedBy;

    @Column(name = "AcknowledgementNote", length = 500)
    private String acknowledgementNote;

    /** Escalade automatique au portail supérieur (Superviseur) après le délai sans résolution. */
    @Column(name = "EscalatedAt")
    private LocalDateTime escalatedAt;
}
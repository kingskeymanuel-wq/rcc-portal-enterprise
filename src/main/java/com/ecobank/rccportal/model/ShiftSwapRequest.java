package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Demande de permutation de shift entre deux agents de la même équipe — workflow à 2 étapes :
 * <ol>
 *   <li>L'agent demandeur (requestedBy) propose d'échanger SON shift du jour requesterDate
 *       avec le shift du jour targetDate de l'agent visé (targetUser). PeerStatus = PENDING.</li>
 *   <li>L'agent visé accepte ou refuse (peerStatus -> ACCEPTED/REJECTED). Un refus arrête tout
 *       ici — jamais transmis au Team Leader.</li>
 *   <li>Si accepté, la demande passe au Team Leader de l'équipe (teamLeaderStatus = PENDING).
 *       Le Team Leader valide ou refuse. Une validation échange RÉELLEMENT les deux
 *       AgentSchedule en base (voir ShiftSwapService.decideByTeamLeader) ; un refus à cette
 *       étape annule définitivement la demande (l'agent doit en soumettre une nouvelle).</li>
 * </ol>
 * Volontairement une entité dédiée plutôt qu'un WorkflowRequest générique : une permutation a
 * besoin de DEUX agents et DEUX dates avec leur propre statut d'étape, ce que le modèle
 * générique à un seul demandeur/assigné ne représente pas proprement.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "ShiftSwapRequests", schema = "dbo")
public class ShiftSwapRequest extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "SwapRequestId")
    private Integer swapRequestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "RequesterUserId", nullable = false)
    private User requester;

    /** Jour du shift de REQUESTER proposé à l'échange. */
    @Column(name = "RequesterDate", nullable = false)
    private LocalDate requesterDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "TargetUserId", nullable = false)
    private User targetUser;

    /** Jour du shift de TARGET proposé à l'échange — peut différer de requesterDate (permutation
     *  libre jour/shift différents, pas uniquement un miroir même jour). */
    @Column(name = "TargetDate", nullable = false)
    private LocalDate targetDate;

    /** "PENDING" | "ACCEPTED" | "REJECTED" — décision de l'agent visé (targetUser). */
    @Column(name = "PeerStatus", nullable = false, length = 20)
    @Builder.Default
    private String peerStatus = "PENDING";

    /** "NOT_SUBMITTED" | "PENDING" | "APPROVED" | "REJECTED" — décision du Team Leader, n'existe
     *  qu'une fois peerStatus = ACCEPTED (NOT_SUBMITTED avant ça, jamais montré au Team Leader). */
    @Column(name = "TeamLeaderStatus", nullable = false, length = 20)
    @Builder.Default
    private String teamLeaderStatus = "NOT_SUBMITTED";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "DecidedByTeamLeaderUserId")
    private User decidedByTeamLeader;

    @Column(name = "TeamLeaderComment", length = 500)
    private String teamLeaderComment;

    /** Message optionnel du demandeur à l'agent visé, expliquant le motif de l'échange. */
    @Column(name = "RequesterMessage", length = 500)
    private String requesterMessage;

    @Column(name = "PeerDecidedAt")
    private LocalDateTime peerDecidedAt;

    @Column(name = "TeamLeaderDecidedAt")
    private LocalDateTime teamLeaderDecidedAt;

    /** true une fois les deux AgentSchedule réellement échangés en base (à l'approbation TL) —
     *  filet de sécurité contre un double-échange si decideByTeamLeader était appelé deux fois. */
    @Column(name = "SwapApplied", nullable = false)
    @Builder.Default
    private boolean swapApplied = false;
}

package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * Note / tâche — soit personnelle (chacun peut noter une tâche pour soi-même, onglet Notes,
 * ouvert à tout utilisateur), soit assignée par la QA à un agent conseiller précis ou à toute
 * une équipe (onglet QA du Workflow) — dans ce second cas, une notification (en app + e-mail,
 * reçu dans Outlook) est envoyée au(x) destinataire(s) à la création.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "AssignedTasks", schema = "dbo")
public class AssignedTask extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TaskId")
    private Integer taskId;

    @Column(name = "Title", nullable = false, length = 200)
    private String title;

    @Column(name = "Description", length = 2000)
    private String description;

    /** Destinataire individuel — si renseigné, prioritaire sur AssignedToTeamCode. Null = tâche perso (créateur = destinataire implicite). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "AssignedToUserId")
    private User assignedTo;

    /** Équipe entière destinataire (ex. "INBOUND") — visible par tous les membres, null si tâche individuelle/perso. */
    @Column(name = "AssignedToTeamCode", length = 50)
    private String assignedToTeamCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CreatedByUserId", nullable = false)
    private User createdByUser;

    @Column(name = "DueDate")
    private LocalDate dueDate;

    /** "LOW" | "NORMAL" | "HIGH" — défaut NORMAL si non renseigné. */
    @Column(name = "Priority", length = 10)
    @Builder.Default
    private String priority = "NORMAL";

    /** "OPEN" | "DONE" — pour les tâches de suivi présence/coaching QA, DONE = signé par l'agent (attestation). */
    @Column(name = "Status", nullable = false, length = 20)
    private String status;

    /** Null = tâche perso/générique (inchangé). "ATTENDANCE_LATE" | "ATTENDANCE_ABSENCE" |
     *  "QA_COACHING" — voir TeamLeaderFollowUpService. Sert au filtrage RH/Superviseur. */
    @Column(name = "Category", length = 30)
    private String category;

    /** Uniquement pour Category=ATTENDANCE_* — true = retard/absence justifié, false = injustifié. */
    @Column(name = "Justified")
    private Boolean justified;

    /** Date du retard/absence, ou date de l'évaluation QA concernée. */
    @Column(name = "RelatedDate")
    private java.time.LocalDate relatedDate;

    /** Meeting tête-à-tête lié (Category = MEETING_REPORT pour le TL, MEETING_ACK pour l'agent). */
    @Column(name = "RelatedMeetingId")
    private Long relatedMeetingId;
}

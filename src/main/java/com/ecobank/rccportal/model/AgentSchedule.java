package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Planning prévisionnel d'un agent pour un jour donné — importé en masse
 * depuis un fichier Excel (matricule / date / heure de début) OU planifié
 * directement par Excelliam (voir ScheduleService.planifyShifts). Sert de
 * référence pour détecter les retards : comparé à l'heure réelle de
 * connexion (ShiftEvent LOGIN).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "AgentSchedules", schema = "dbo",
        uniqueConstraints = @UniqueConstraint(columnNames = {"UserId", "WorkDate"}))
public class AgentSchedule extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ScheduleId")
    private Integer scheduleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @Column(name = "WorkDate", nullable = false)
    private LocalDate workDate;

    /** Null pour un jour sans prise de poste (OFF/ABS/RM/P/PU/Congés — voir shiftCode). */
    @Column(name = "PlannedStartTime")
    private LocalTime plannedStartTime;

    /** Null pour un jour sans prise de poste, ou si l'import ne fournissait qu'une heure de
     *  début (ancien format matricule/date/heure). */
    @Column(name = "PlannedEndTime")
    private LocalTime plannedEndTime;

    /** Code brut tel qu'importé (M, M2, OFF, ABS, C...) — voir la légende du fichier source.
     *  Null pour un import à l'ancien format (matricule/date/heure de début seule). */
    @Column(name = "ShiftCode", length = 20)
    private String shiftCode;

    /** Libellé humain du code (ex. "Matin 08h-17h", "Congés") — affiché tel quel côté planning. */
    @Column(name = "ShiftLabel", length = 100)
    private String shiftLabel;

    /** true si le shift commence un jour et finit le lendemain (ex. Nuit 21h-07h) — pour ne
     *  jamais interpréter plannedEndTime &lt; plannedStartTime comme une erreur de saisie. */
    @Column(name = "OvernightCrossesMidnight", nullable = false)
    @Builder.Default
    private boolean overnightCrossesMidnight = false;

    /**
     * "APPROVED" | "PENDING" | "REJECTED" — uniquement significatif pour un planning saisi
     * par Excelliam (voir ScheduleService.planifyShifts) : le Team Leader de l'équipe doit
     * valider AVANT que les agents ne voient leur planning (planningForUser filtre sur
     * APPROVED uniquement). L'import RH classique (importFromExcel) reste APPROVED d'emblée
     * — seul le flux Excelliam introduit cette étape de validation, voir la demande métier.
     * Toujours APPROVED pour un import RH ou toute ligne créée avant l'introduction de ce
     * champ (valeur par défaut), donc aucune régression sur le planning déjà en place.
     */
    @Column(name = "ApprovalStatus", length = 20, nullable = false)
    @Builder.Default
    private String approvalStatus = "APPROVED";

    /** Motif du refus quand approvalStatus = "REJECTED", saisi par le Team Leader — visible
     *  par Excelliam pour ajuster le planning. Null tant qu'aucun refus n'a eu lieu. */
    @Column(name = "RejectionReason", length = 500)
    private String rejectionReason;

    /**
     * "EXCELLIAM" (par défaut — flux historique : Excelliam planifie via planifyShifts ou
     * import RH, le Team Leader valide) ou "TEAM_LEADER" (nouveau flux symétrique : le Team
     * Leader planifie lui-même sa propre équipe et l'envoie à Excelliam pour validation).
     * Détermine qui doit décider quand approvalStatus = "PENDING", et permet l'état
     * intermédiaire "VALIDATED" propre au flux TEAM_LEADER (Excelliam a validé, mais ce n'est
     * en ligne qu'après le clic "Mise à jour" du Team Leader — voir ScheduleService).
     */
    @Column(name = "Origin", length = 20, nullable = false)
    @Builder.Default
    private String origin = "EXCELLIAM";
}

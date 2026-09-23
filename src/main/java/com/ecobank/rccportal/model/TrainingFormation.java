package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Formation programmable (mois/semaine/jour) avec verrouillage de la vitesse
 * vidéo maximale et suivi obligatoire du parcours (scroll + vidéo) par agent.
 * RecurrenceType permet de reprogrammer automatiquement la même formation
 * chaque semaine/mois (ex. rappel mensuel obligatoire).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "TrainingFormations", schema = "dbo")
public class TrainingFormation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "FormationId")
    private Integer formationId;

    @Column(name = "Title", nullable = false, length = 200)
    private String title;

    @Lob
    @Column(name = "Description")
    private String description;

    /** ex. COMPTE, TRANSFERT, CARTE ATM... ou "Général" */
    @Column(name = "Category", length = 100)
    private String category;

    /** Date à laquelle la formation est programmée (jour). */
    @Column(name = "ScheduledDate", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "ScheduledTime")
    private LocalTime scheduledTime;

    /** NONE | WEEKLY | MONTHLY — reprogrammation automatique. */
    @Column(name = "RecurrenceType", nullable = false, length = 20)
    @Builder.Default
    private String recurrenceType = "NONE";

    @Column(name = "DurationMinutes")
    private Integer durationMinutes;

    /** Vitesse maximale de lecture vidéo autorisée (verrouillée côté lecteur). */
    @Column(name = "VideoMaxPlaybackRate", nullable = false)
    @Builder.Default
    private Double videoMaxPlaybackRate = 1.5;

    /** Seuil (%) de parcours (scroll+vidéo) exigé pour considérer la leçon complétée. */
    @Column(name = "CompletionThresholdPercent", nullable = false)
    @Builder.Default
    private Integer completionThresholdPercent = 95;

    /** DRAFT | PLANNED | ACTIVE | ARCHIVED */
    @Column(name = "Status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PLANNED";

    /** Équipe cible (null = toutes les équipes / activity de USERS). */
    @Column(name = "TargetTeam", length = 100)
    private String targetTeam;

    /** Formation obligatoire — apparaît en priorité/alerte tant que non complétée. */
    @Column(name = "Mandatory", nullable = false)
    @Builder.Default
    private Boolean mandatory = true;

    @Column(name = "CreatedByUserId")
    private Long createdByUserId;

    @OneToMany(mappedBy = "formation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private List<TrainingLesson> lessons = new ArrayList<>();
}

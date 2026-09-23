package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Compétition d'évaluation entre équipes — créée par QA/le formateur, sur un jeu
 * d'évaluation existant (GameDefinition). Workflow :
 * DRAFT → le formateur choisit les équipes (ou coche "stagiaires uniquement")
 * PENDING_VALIDATION → chaque Team Leader concerné doit valider les membres de son équipe
 * (sauf si isTraineeOnly=true, où le formateur choisit directement les participants)
 * SCHEDULED → tous les Team Leaders concernés ont validé, en attente de la date programmée
 * ACTIVE → à partir de scheduledAt, les participants validés peuvent jouer
 * COMPLETED → clôturée manuellement ou implicitement (tous ont joué)
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "GameCompetitions", schema = "dbo")
public class GameCompetition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CompetitionId")
    private Integer competitionId;

    @Column(name = "GameKey", nullable = false, length = 50)
    private String gameKey;

    @Column(name = "Title", nullable = false, length = 200)
    private String title;

    @Column(name = "ScheduledAt", nullable = false)
    private LocalDateTime scheduledAt;

    /** DRAFT | PENDING_VALIDATION | SCHEDULED | ACTIVE | COMPLETED */
    @Column(name = "Status", nullable = false, length = 30)
    @Builder.Default
    private String status = "DRAFT";

    /** Si true : réservée aux stagiaires — le formateur choisit directement les participants,
     *  aucune validation par Team Leader n'est nécessaire. */
    @Column(name = "IsTraineeOnly", nullable = false)
    @Builder.Default
    private Boolean isTraineeOnly = false;

    @Column(name = "CreatedByUsername", length = 100)
    private String createdByUsername;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

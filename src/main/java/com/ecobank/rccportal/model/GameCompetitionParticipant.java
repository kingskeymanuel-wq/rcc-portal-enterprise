package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Un participant retenu pour une compétition — visible dans son onglet Évaluation dès que
 *  son équipe est validée ET que la date programmée est atteinte. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "GameCompetitionParticipants", schema = "dbo")
public class GameCompetitionParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ParticipantId")
    private Integer participantId;

    @Column(name = "CompetitionId", nullable = false)
    private Integer competitionId;

    @Column(name = "UserId", nullable = false)
    private Long userId;

    /** Code TeamClassifier.Team — l'équipe pour laquelle ce participant marque des points. */
    @Column(name = "Team", nullable = false, length = 30)
    private String team;

    @Column(name = "Score")
    private Integer score;

    @Column(name = "CompletedAt")
    private LocalDateTime completedAt;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Une équipe engagée dans une compétition — le Team Leader doit valider ses participants
 *  (voir GameCompetitionParticipant) avant que "Validated" ne passe à true. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "GameCompetitionTeams", schema = "dbo")
public class GameCompetitionTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CompetitionTeamId")
    private Integer competitionTeamId;

    @Column(name = "CompetitionId", nullable = false)
    private Integer competitionId;

    /** Code TeamClassifier.Team (INBOUND_VOICE, INBOUND_MAIL, CIB, OUTBOUND). */
    @Column(name = "Team", nullable = false, length = 30)
    private String team;

    @Column(name = "Validated", nullable = false)
    @Builder.Default
    private Boolean validated = false;

    @Column(name = "ValidatedAt")
    private LocalDateTime validatedAt;

    @Column(name = "ValidatedByUsername", length = 100)
    private String validatedByUsername;
}

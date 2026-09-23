package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "GameScores", schema = "dbo")
public class GameScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ScoreId")
    private Integer scoreId;

    @Column(name = "GameKey", nullable = false, length = 50)
    private String gameKey;

    @Column(name = "UserId", nullable = false)
    private Long userId;

    @Column(name = "Score", nullable = false)
    private Integer score;

    @Column(name = "CorrectCount")
    private Integer correctCount;

    @Column(name = "TotalCount")
    private Integer totalCount;

    @Column(name = "PlayedAt", nullable = false)
    @Builder.Default
    private LocalDateTime playedAt = LocalDateTime.now();
}

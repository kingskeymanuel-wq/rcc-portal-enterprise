package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Historique des analyses de données générées — chaque appel à "Générer l'analyse" (voir
 * DataAnalysisService.analyze()) enregistre son résultat ici, que la synthèse ait été produite
 * par l'IA (Anthropic Claude) ou, à défaut, par le moteur de règles automatique
 * (DataAnalysisService.generateRuleBasedNarrative() — jamais de blocage si l'IA n'est pas
 * configurée). Permet de consulter les analyses passées sans avoir à les regénérer.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "DataAnalysisSnapshots", schema = "dbo")
public class DataAnalysisSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "SnapshotId")
    private Integer snapshotId;

    @Column(name = "PeriodMonth", nullable = false, length = 7)
    private String periodMonth;

    /** Équipe filtrée au moment de la génération, NULL = vue globale toutes équipes. */
    @Column(name = "TeamFilter", length = 100)
    private String teamFilter;

    @Column(name = "Narrative", columnDefinition = "NVARCHAR(MAX)", nullable = false)
    private String narrative;

    /** true = généré par Claude, false = moteur de règles automatique (voir classe ci-dessus). */
    @Column(name = "GeneratedByAi", nullable = false)
    private Boolean generatedByAi;

    @Column(name = "TotalAgents")
    private Integer totalAgents;

    @Column(name = "AvgPresenceRate")
    private Double avgPresenceRate;

    @Column(name = "AvgQualityScore")
    private Double avgQualityScore;

    @Column(name = "AvgPerformanceGlobale")
    private Double avgPerformanceGlobale;

    @Column(name = "GeneratedByUsername", length = 100)
    private String generatedByUsername;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

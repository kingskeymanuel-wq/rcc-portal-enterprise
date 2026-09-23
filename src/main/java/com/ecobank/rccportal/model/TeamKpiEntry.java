package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * KPI agrégé au niveau équipe/pôle (pas d'agent) — pour les fichiers de type "FOCUS PAR
 * PÔLE" (une ligne par métrique, une colonne par mois), structurellement différents des
 * fichiers KPI par agent (voir ManualKpiEntry). Aucune tentative de rattacher ces valeurs
 * à un agent précis — l'information n'existe pas dans ce type de fichier, et l'inventer
 * serait fabriquer une donnée.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "TeamKpiEntries", schema = "dbo")
public class TeamKpiEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TeamKpiEntryId")
    private Integer teamKpiEntryId;

    /** Code TeamClassifier.Team (INBOUND_VOICE, INBOUND_MAIL, CIB, OUTBOUND) — choisi par
     *  l'importeur au moment de l'import, le fichier lui-même n'indique jamais l'équipe. */
    @Column(name = "Team", nullable = false, length = 30)
    private String team;

    @Column(name = "MetricCode", nullable = false, length = 50)
    private String metricCode;

    @Column(name = "MetricValue", nullable = false, precision = 18, scale = 4)
    private BigDecimal metricValue;

    @Column(name = "PeriodDate", nullable = false)
    private LocalDate periodDate;

    @Column(name = "EnteredByUsername", length = 100)
    private String enteredByUsername;

    @Column(name = "ImportBatchId", length = 40)
    private String importBatchId;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

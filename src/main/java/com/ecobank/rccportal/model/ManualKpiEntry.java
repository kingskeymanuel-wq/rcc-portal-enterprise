package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "ManualKpiEntries", schema = "dbo")
public class ManualKpiEntry extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ManualKpiEntryId")
    private Integer manualKpiEntryId;

    /** Agent concerné par le KPI. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "SubjectUserId", nullable = false)
    private User subject;

    /** Superviseur/QA qui a saisi la valeur. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "EnteredByUserId", nullable = false)
    private User enteredBy;

    /** ex. 'CSAT', 'AHT', 'FCR' */
    @Column(name = "MetricCode", nullable = false, length = 50)
    private String metricCode;

    @Column(name = "MetricValue", nullable = false, precision = 18, scale = 4)
    private BigDecimal metricValue;

    @Column(name = "PeriodDate", nullable = false)
    private LocalDate periodDate;

    /** Identifie le fichier importé qui a créé cette ligne — permet de le supprimer d'un bloc. Null pour une saisie manuelle unitaire. */
    @Column(name = "ImportBatchId", length = 40)
    private String importBatchId;
}

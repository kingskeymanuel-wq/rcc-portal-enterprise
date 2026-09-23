package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "Procedures", schema = "dbo")
public class Procedure extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ProcedureId")
    private Integer procedureId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ZoneId", nullable = false)
    private ProcedureZone zone;

    /** Service/équipe concerné — null = procédure générique, visible par toutes les équipes. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ServiceId")
    private RccService service;

    /** Filiale concernée (code pays, ex. "CI") — null = valable pour toutes les filiales. */
    @Column(name = "CountryCode", length = 3)
    private String countryCode;

    @Column(name = "Title", nullable = false, length = 200)
    private String title;

    /** Délai annoncé au client — ex. "24h", "5 jours ouvrés", "Non défini dans la procédure". */
    @Column(name = "SlaDelay", length = 100)
    private String slaDelay;

    /** Niveau de traitement — "N1", "N2", "P1" (critique)... */
    @Column(name = "Level", length = 20)
    private String level;

    /** Équipe(s) responsable(s) du traitement — ex. "RCC Résolution, Compliance". */
    @Column(name = "ResponsibleTeam", length = 200)
    private String responsibleTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CreatedByUserId")
    private User createdBy;
}

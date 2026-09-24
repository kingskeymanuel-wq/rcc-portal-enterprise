package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * Point de disponibilité des cartes par agence, à une date donnée (tableau transmis par la
 * filiale : DATE | AGENCE | CARTE | CODE | TYPE DE CARTE). « Carte » = cartes physiques en
 * stock à l'agence, « Code » = codes PIN disponibles, « Types » = gammes présentes.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "CardAgencyStatus", schema = "dbo")
public class CardAgencyStatus extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CardAgencyStatusId")
    private Long id;

    @Column(name = "CountryCode", length = 2, nullable = false)
    private String countryCode;

    @Column(name = "ReportDate", nullable = false)
    private LocalDate reportDate;

    /** Nom de l'agence sans son code (« NIANGON »). */
    @Column(name = "Agency", length = 150, nullable = false)
    private String agency;

    /** Code agence (« K27 »), facultatif. */
    @Column(name = "AgencyCode", length = 20)
    private String agencyCode;

    /** OK, FAIBLE ou RUPTURE. */
    @Column(name = "CardStatus", length = 20, nullable = false)
    private String cardStatus;

    /** OK, FAIBLE ou RUPTURE. */
    @Column(name = "PinStatus", length = 20, nullable = false)
    private String pinStatus;

    /** Gammes disponibles, séparées par des virgules (« CLASSIC, PLATINIUM, GOLD »). */
    @Column(name = "CardTypes", length = 400)
    private String cardTypes;

    @Column(name = "Note", length = 500)
    private String note;

    @Column(name = "UpdatedBy", length = 150)
    private String updatedBy;
}

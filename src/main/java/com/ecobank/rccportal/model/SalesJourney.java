package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Parcours de vente interactif d'un produit (jeux d'Outbound — voir outbound-dashboard.html).
 * Administrable par QA/Admin ou par le Team Leader de l'équipe Outbound (voir
 * SalesJourneyController) — remplace l'ancien fichier statique sales-journeys-data.js : QA
 * peut désormais ajouter un nouveau produit sans intervention développeur.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "SalesJourneys", schema = "dbo")
public class SalesJourney {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "JourneyId")
    private Integer journeyId;

    @Column(name = "JourneyKey", nullable = false, unique = true, length = 60)
    private String journeyKey;

    @Column(name = "Title", nullable = false, length = 150)
    private String title;

    @Column(name = "Icon", length = 50)
    private String icon;

    @Column(name = "ColorFrom", length = 20)
    private String colorFrom;

    @Column(name = "ColorTo", length = 20)
    private String colorTo;

    @Column(name = "Pitch", length = 300)
    private String pitch;

    /** JSON : [{"title":"1. Découverte","content":["question 1","question 2"]}, ...] */
    @Column(name = "StepsJson", columnDefinition = "NVARCHAR(MAX)", nullable = false)
    private String stepsJson;

    @Column(name = "SortOrder", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "Active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "UpdatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}

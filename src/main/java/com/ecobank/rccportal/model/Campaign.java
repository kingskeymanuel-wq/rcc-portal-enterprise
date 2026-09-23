package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Campagne d'appels Outbound — créée par le Team Leader de l'équipe Outbound (ou QA/Admin).
 * Regroupe une liste de contacts (CampaignContact) à appeler, répartis entre les agents.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "Campaigns", schema = "dbo")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CampaignId")
    private Integer campaignId;

    @Column(name = "Name", nullable = false, length = 200)
    private String name;

    @Column(name = "Description", length = 1000)
    private String description;

    @Column(name = "CreatedByUserId", nullable = false)
    private Long createdByUserId;

    @Column(name = "StartDate")
    private LocalDate startDate;

    @Column(name = "EndDate")
    private LocalDate endDate;

    /** ACTIVE | CLOSED */
    @Column(name = "Status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    /** Service visé pour l'onglet "Campagne" agent : "DIGITAL" | "TELEVENTE" | null (toutes). */
    @Column(name = "TargetService", length = 20)
    private String targetService;

    @Column(name = "IconClass", length = 50)
    @Builder.Default
    private String iconClass = "bi-megaphone-fill";

    @Column(name = "ColorFrom", length = 10)
    @Builder.Default
    private String colorFrom = "#0057B8";

    @Column(name = "ColorTo", length = 10)
    @Builder.Default
    private String colorTo = "#00A651";

    /** Image de couverture de la tuile campagne (grille "Campagne", façon Microsoft Forms —
     *  voir les captures) — soit une URL choisie dans CampaignCoverLibrary (bibliothèque de
     *  photos thématiques prédéfinies), soit une URL externe collée par le Team Leader. Null =
     *  repli sur le dégradé de couleur + icône (comportement d'avant ce champ, jamais cassé). */
    @Column(name = "CoverImageUrl", length = 500)
    private String coverImageUrl;

    /** Modèle de questions de la campagne — JSON (liste de CampaignFieldDto sérialisés),
     *  affiché dynamiquement dans la modale d'appel agent en plus des 4 boutons de statut fixes
     *  (À appeler/Interaction/Pas de réponse/RDV pris). Null ou vide = aucune question additionnelle. */
    @Column(name = "FieldsJson", length = 4000)
    private String fieldsJson;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

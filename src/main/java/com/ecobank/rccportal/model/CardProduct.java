package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Une carte proposée par une filiale (ex. « Visa Classic », « Carte prépayée ») — lignes de
 * l'onglet « Disponibilité des cartes » de la Base de connaissances. Sa disponibilité par
 * ville est dans {@link CardAvailability}, cochée par QA.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "CardProducts", schema = "dbo")
public class CardProduct extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CardProductId")
    private Long id;

    /** Filiale — FK logique vers KnowledgeCountry.countryCode (ex. "CI", "SN"). */
    @Column(name = "CountryCode", length = 2, nullable = false)
    private String countryCode;

    @Column(name = "Name", length = 150, nullable = false)
    private String name;

    /** Famille : Débit, Crédit, Prépayée… (texte libre). */
    @Column(name = "Category", length = 80)
    private String category;

    /** Détails utiles à l'agent : délai de délivrance, frais, plafonds, conditions… */
    @Column(name = "Details", length = 1000)
    private String details;

    @Builder.Default
    @Column(name = "SortOrder", nullable = false)
    private Integer sortOrder = 0;

    @Builder.Default
    @Column(name = "IsActive", nullable = false)
    private Boolean active = true;
}

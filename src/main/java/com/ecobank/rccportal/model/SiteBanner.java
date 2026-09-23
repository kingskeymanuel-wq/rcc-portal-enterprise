package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Bannière d'accueil — une seule ligne (singleton applicatif, pas une liste).
 * Modifiable par l'administrateur ou le service Quality Assurance (communication).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "SiteBanner", schema = "dbo")
public class SiteBanner extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "SiteBannerId")
    private Integer siteBannerId;

    @Column(name = "ImageUrl", length = 500)
    private String imageUrl;

    @Column(name = "Headline", length = 200)
    private String headline;

    @Column(name = "Subheadline", length = 500)
    private String subheadline;

    @Column(name = "CtaLabel", length = 100)
    private String ctaLabel;

    @Column(name = "CtaUrl", length = 500)
    private String ctaUrl;
}

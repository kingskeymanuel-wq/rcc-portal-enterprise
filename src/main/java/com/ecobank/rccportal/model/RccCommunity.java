package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Communauté MON RCC — propre à ce portail, gérée par l'administrateur.
 * Distincte de dbo.SERVICES (table réelle du système bancaire central,
 * partagée avec d'autres applications Ecobank — jamais utilisée ici).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "RccCommunities", schema = "dbo")
public class RccCommunity extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CommunityId")
    private Integer communityId;

    @Column(name = "CommunityKey", nullable = false, unique = true, length = 50)
    private String communityKey;

    @Column(name = "Label", nullable = false, length = 100)
    private String label;

    @Column(name = "SortOrder", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}

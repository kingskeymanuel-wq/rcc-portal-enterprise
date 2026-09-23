package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Carte "fonctionnalité" affichée sur la page de connexion (ex. "Sécurité
 * Enterprise", "Knowledge Base"...) — éditable depuis le portail IT
 * (icône, titre, sous-titre, ordre, active/inactive), plutôt que codée en dur.
 * Le style commun (police, interligne) est géré séparément via SiteSetting
 * (voir SiteSettingService.LOGIN_FEATURES_FONT_FAMILY / LINE_HEIGHT) — il
 * s'applique à toutes les cartes à la fois, pas carte par carte.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "LoginFeatureCards", schema = "dbo")
public class LoginFeatureCard extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CardId")
    private Integer cardId;

    /** Classe d'icône Bootstrap Icons, ex. "bi-shield-lock-fill". */
    @Column(name = "Icon", nullable = false, length = 50)
    private String icon;

    @Column(name = "Title", nullable = false, length = 100)
    private String title;

    @Column(name = "Subtitle", length = 200)
    private String subtitle;

    @Column(name = "SortOrder", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "Active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}

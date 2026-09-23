package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Autorisation explicite (accordée ou retirée) d'une fonctionnalité pour un
 * utilisateur précis — remplace, quand elle existe, le comportement par
 * défaut basé sur le rôle (data-roles côté sidebar). Pas de ligne = pas de
 * dérogation, le rôle continue de décider seul.
 *
 * FeatureCode correspond au segment d'URL de la page (ex. "users",
 * "performance", "mon-rcc", "workflow", "qa"...).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "UserFeaturePermissions", schema = "dbo",
        uniqueConstraints = @UniqueConstraint(columnNames = {"UserId", "FeatureCode"}))
public class UserFeaturePermission extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UserFeaturePermissionId")
    private Integer userFeaturePermissionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @Column(name = "FeatureCode", nullable = false, length = 50)
    private String featureCode;

    @Column(name = "IsAllowed", nullable = false)
    private Boolean isAllowed;
}

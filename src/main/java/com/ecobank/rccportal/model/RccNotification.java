package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "RccNotifications", schema = "dbo")
public class RccNotification extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "NotificationId")
    private Integer notificationId;

    /** null = notification globale (comportement actuel du frontend). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TargetUserId")
    private User targetUser;

    @Column(name = "Content", nullable = false, length = 500)
    private String content;

    @Column(name = "IsRead", nullable = false)
    private Boolean isRead;

    /** ex. "UNLOCK_ACCOUNT" — null pour une notification purement informative sans action possible. */
    @Column(name = "ActionType", length = 50)
    private String actionType;

    /** Cible de l'action (ex. le matricule du compte à réactiver) — null si actionType est null. */
    @Column(name = "ActionTarget", length = 100)
    private String actionTarget;

    /** Notification commune (targetUser = null) réservée à une filiale (code pays), null = toutes. */
    @Column(name = "AudienceCountry", length = 3)
    private String audienceCountry;

    /** Profils concernés, séparés par des virgules (ex. "AGENT,TEAM_LEADER"), null = règle par défaut. */
    @Column(name = "AudienceRoles", length = 200)
    private String audienceRoles;

    /** Service concerné (code), null = tous les services. */
    @Column(name = "AudienceServiceCode", length = 50)
    private String audienceServiceCode;
}

package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "USERS", schema = "dbo")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID", nullable = false)
    private Long id;

    @Column(name = "ACCOUNT_DATE_EXPIRED")
    private LocalDate accountDateExpired;

    @Column(name = "ACCOUNT_ENABLED")
    private Boolean accountEnabled;

    @Column(name = "ACCOUNT_EXPIRED")
    private Boolean accountExpired;

    @Column(name = "ACCOUNT_LOCKED")
    private Boolean accountLocked;

    @Column(name = "AUTHORIZER", length = 225)
    private String authorizer;

    @Column(name = "CREATED_AT")
    private OffsetDateTime createdAt;

    @Column(name = "CREDENTIALS_EXPIRED")
    private Boolean credentialsExpired;

    @Column(name = "EMAIL", length = 255)
    private String email;

    @Column(name = "FAILED_ATTEMPTS", nullable = false)
    private Integer failedAttempts;

    @Column(name = "INPUTTER", length = 20)
    private String inputter;

    @Column(name = "MODIFIED_AT")
    private OffsetDateTime modifiedAt;

    @Column(name = "NAME", length = 200)
    private String name;

    @Column(name = "STATUS", length = 20)
    private String status;

    @Column(name = "USERNAME", nullable = false, length = 35)
    private String username;

    @Column(name = "AFFILIATE_ID")
    private Long affiliateId;

    /*
     * Ne jamais stocker ici le mot de passe Active Directory.
     * Ce champ reste uniquement pour compatibilité avec le schéma existant.
     */
    @Column(name = "PASSWORD", length = 255)
    private String password;

    @Column(name = "AFFILIATE_BRANCH", length = 3)
    private String affiliateBranch;

    /** "M" | "F" */
    @Column(name = "GENDER", length = 1)
    private String gender;

    /** ex. "Ecobank" | "Outsource" */
    @Column(name = "CONTRACT_TYPE", length = 30)
    private String contractType;

    /** ex. "CDI" | "CDD" */
    @Column(name = "CONTRACT_STATUS", length = 50)
    private String contractStatus;

    @Column(name = "CONTRACT_START_DATE")
    private LocalDate contractStartDate;

    @Column(name = "CONTRACT_END_DATE")
    private LocalDate contractEndDate;

    /** Fonction précise (ex. "TEAM LEADER INBOUND VOICE") — plus fine que l'équipe (activity). */
    @Column(name = "ROLE_DETAIL", length = 150)
    private String roleDetail;

    /** Équipe (ex. "INBOUND", "OUTBOUND", "OPERATIONS", "CIB") — utilisée partout comme "équipe"
     *  dans Reporting/Shift/Analyse de données (import roster : colonne "Pôle d'activité"). */
    @Column(name = "ACTIVITY", length = 100)
    private String activity;

    /** Lieu d'habitation de l'agent — visible dans Mon Parcours, modifiable par l'admin uniquement (voir UserController.update / requireAdmin). */
    @Column(name = "RESIDENCE_PLACE", length = 200)
    private String residencePlace;

    /** Équipe dirigée par ce Team Leader (INBOUND_VOICE/INBOUND_MAIL/CIB/OUTBOUND) — affectation
     *  explicite configurée par l'admin (voir UserController.update), sans rapport avec ACTIVITY
     *  qui reste la propre équipe d'appartenance de l'utilisateur. Null pour tout le monde sauf
     *  les comptes ayant le rôle TEAM_LEADER. */
    @Column(name = "LED_TEAM", length = 30)
    private String ledTeam;

    /**
     * Filiale/service/équipe choisis une seule fois à la première connexion (voir
     * FirstLoginController) — une fois vrai, l'utilisateur ne peut plus les modifier
     * lui-même ; seul l'IT peut corriger une erreur, depuis Administration.
     */
    @Column(name = "TEAM_ASSIGNMENT_LOCKED", nullable = false)
    @Builder.Default
    private Boolean teamAssignmentLocked = false;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (failedAttempts == null) {
            failedAttempts = 0;
        }
        if (accountEnabled == null) {
            accountEnabled = true;
        }
        if (accountExpired == null) {
            accountExpired = false;
        }
        if (accountLocked == null) {
            accountLocked = false;
        }
        if (credentialsExpired == null) {
            credentialsExpired = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        modifiedAt = OffsetDateTime.now();
    }
}
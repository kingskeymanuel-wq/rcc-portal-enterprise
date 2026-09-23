package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Fiche d'un pôle RCC (Inbound, Outbound, Résolution, Opérations, Business/Agency
 * Banking, Agences...) — reprend le format des fiches pôle diffusées en interne :
 * manager désigné, contact direct, description "Qui sommes-nous / Que faisons-nous",
 * et la liste de ses activités/délais portée par {@link SlaRule} (SlaRule.pole).
 *
 * Un pôle n'est pas forcément une {@link RccService} existante (les services du
 * modèle organisationnel agent/équipe et les pôles affichés ici — orientés
 * traitement de réclamations/activités — ne se recoupent pas toujours), d'où une
 * entité dédiée plutôt qu'une réutilisation forcée de RccService.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "RccPoles", schema = "dbo")
public class RccPole extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PoleId")
    private Integer poleId;

    /** Ex. "Pôle Inbound", "Pôle Outbound", "Pôle Résolution", "Agences". */
    @Column(name = "Name", nullable = false, length = 150)
    private String name;

    /** Compte portail du manager désigné — cible des alertes/assignations. Nullable :
     *  une fiche peut être créée avant que le compte du manager existe dans le portail. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ManagerUserId")
    private User manager;

    /** Numéro de contact direct affiché sur la fiche (peut différer du profil du manager). */
    @Column(name = "ContactPhone", length = 40)
    private String contactPhone;

    /** E-mail de contact affiché sur la fiche (peut différer de l'e-mail du compte manager,
     *  ex. une boîte fonctionnelle comme assist@ecobank.com). */
    @Column(name = "ContactEmail", length = 150)
    private String contactEmail;

    /** Numéro vert / long, boîte fonctionnelle de l'équipe (ex. "9955", "2721210021"). */
    @Column(name = "TeamContactLabel", length = 100)
    private String teamContactLabel;

    @Column(name = "WhoWeAre", length = 1000)
    private String whoWeAre;

    @Column(name = "WhatWeDo", length = 1000)
    private String whatWeDo;

    @Column(name = "IsActive", nullable = false)
    private Boolean isActive;

    @Column(name = "SortOrder", nullable = false)
    private Integer sortOrder;
}

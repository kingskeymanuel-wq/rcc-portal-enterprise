package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Une agence Ecobank (siège, agence, guichet) rattachée à une filiale (voir
 * {@link KnowledgeCountry#getCountryCode()}) et une ville — alimente l'onglet
 * "Carte des banques" de la Base de connaissance (KnowledgeApiController /
 * knowledge.html) : sélection du pays → liste des villes → agences de la ville
 * avec position réelle (latitude/longitude, saisies via recherche d'adresse
 * OpenStreetMap côté admin, jamais Google Maps — voir bank-map.js) et contacts.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "BankBranches", schema = "dbo")
public class BankBranch extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BranchId")
    private Long branchId;

    /** Filiale — FK logique vers KnowledgeCountry.countryCode (ex. "CI", "SN"). */
    @Column(name = "CountryCode", length = 2, nullable = false)
    private String countryCode;

    @Column(name = "City", length = 100, nullable = false)
    private String city;

    /** Nom de l'agence, ex. "Ecobank Plateau — Siège", "Agence Cocody ENA". */
    @Column(name = "Name", length = 200, nullable = false)
    private String name;

    @Column(name = "Address", length = 500)
    private String address;

    @Column(name = "Latitude")
    private Double latitude;

    @Column(name = "Longitude")
    private Double longitude;

    @Column(name = "Phone", length = 50)
    private String phone;

    @Column(name = "Email", length = 150)
    private String email;

    /** Texte libre, ex. "Lun–Ven 08h00–16h00, Sam 08h30–12h30". */
    @Column(name = "OpeningHours", length = 200)
    private String openingHours;

    @Column(name = "ManagerName", length = 150)
    private String managerName;

    /** Ex. "Siège", "Agence", "Guichet", "Bureau de change". */
    @Column(name = "BranchType", length = 50)
    private String branchType;

    @Column(name = "IsActive", nullable = false)
    @Builder.Default
    private boolean active = true;
}

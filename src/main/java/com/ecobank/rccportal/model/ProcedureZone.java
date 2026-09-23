package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "ProcedureZones", schema = "dbo")
public class ProcedureZone extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ZoneId")
    private Integer zoneId;

    @Column(name = "Code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "Label", nullable = false, length = 100)
    private String label;

    /** Image de la vignette (grille de thématiques) — upload réservé à l'administrateur. */
    @Column(name = "ImageUrl", length = 500)
    private String imageUrl;

    /** INBOUND_VOICE | INBOUND_MAIL | CIB | OUTBOUND — voir TeamClassifier.Team. NULL =
     *  zone historique, traitée comme INBOUND_VOICE par migration. */
    @Column(name = "Team", length = 30)
    private String team;
}

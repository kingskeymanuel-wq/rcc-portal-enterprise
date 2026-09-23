package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/** Case cochée par QA : la carte {@link #cardProductId} est-elle disponible dans {@link #city} ? */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "CardAvailability", schema = "dbo")
public class CardAvailability extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CardAvailabilityId")
    private Long id;

    @Column(name = "CardProductId", nullable = false)
    private Long cardProductId;

    @Column(name = "City", length = 100, nullable = false)
    private String city;

    @Builder.Default
    @Column(name = "Available", nullable = false)
    private Boolean available = false;

    /** Précision pour cette ville : « stock faible », « délai 72h », « agence Plateau uniquement »… */
    @Column(name = "Note", length = 500)
    private String note;

    @Column(name = "UpdatedBy", length = 150)
    private String updatedBy;
}

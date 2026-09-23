package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "QualityMotifs", schema = "dbo")
public class QualityMotif extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "MotifId")
    private Integer motifId;

    @Column(name = "Label", nullable = false, length = 150)
    private String label;

    @Column(name = "IsActive", nullable = false)
    private Boolean isActive;
}

package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "QualityCriterionAttributes", schema = "dbo")
public class QualityCriterionAttribute extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AttributeId")
    private Integer attributeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CriterionId", nullable = false)
    private QualityCriterion criterion;

    @Column(name = "Label", nullable = false, length = 300)
    private String label;

    @Column(name = "SortOrder", nullable = false)
    private Integer sortOrder;
}

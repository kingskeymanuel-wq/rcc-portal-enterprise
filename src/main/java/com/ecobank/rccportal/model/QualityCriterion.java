package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "QualityCriteria", schema = "dbo")
public class QualityCriterion extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CriterionId")
    private Integer criterionId;

    @Column(name = "Code", nullable = false, unique = true, length = 10)
    private String code;

    @Column(name = "Section", nullable = false, length = 50)
    private String section;

    @Column(name = "Name", nullable = false, length = 150)
    private String name;

    @Column(name = "Description", length = 500)
    private String description;

    @Column(name = "Weight", nullable = false)
    private Integer weight;

    @Column(name = "IsKnockOut", nullable = false)
    private Boolean isKnockOut;

    @Column(name = "SortOrder", nullable = false)
    private Integer sortOrder;

    /** VOICE | CHAT | BOTH — critère spécifique à un canal, ou générique. Null traité comme BOTH
     *  (rétrocompatibilité — un critère déjà existant sans cette colonne s'applique partout). */
    @Column(name = "Channel", length = 10)
    private String channel;
}

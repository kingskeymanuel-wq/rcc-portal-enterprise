package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "Teams", schema = "dbo")
public class Team extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TeamId")
    private Integer teamId;

    @Column(name = "Code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "Label", nullable = false, length = 100)
    private String label;

    @Column(name = "IconGlyph", length = 20)
    private String iconGlyph;

    @Column(name = "AccentColor", length = 10)
    private String accentColor;

    @Column(name = "IsActive", nullable = false)
    private Boolean isActive;
}

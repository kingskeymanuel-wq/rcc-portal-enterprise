package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "MailTemplateCategories", schema = "dbo")
public class MailTemplateCategory extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CategoryId")
    private Integer categoryId;

    @Column(name = "Code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "Label", nullable = false, length = 100)
    private String label;

    @Column(name = "IconGlyph", length = 20)
    private String iconGlyph;

    @Column(name = "AccentColor", length = 10)
    private String accentColor;

    @Column(name = "BackgroundColor", length = 10)
    private String backgroundColor;

    @Column(name = "SortOrder", nullable = false)
    private Integer sortOrder;

    /** EMAIL | RAFIKI | SOCIAL — équipe propriétaire de la catégorie. NULL = pas encore classée. */
    @Column(name = "Team", length = 30)
    private String team;
}

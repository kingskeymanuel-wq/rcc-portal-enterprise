package com.ecobank.rccportal.model;
import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "KnowledgeCategories", schema = "dbo")
public class KnowledgeCategory extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CategoryId")
    private Integer categoryId;

    @Column(name = "Code", nullable = false, length = 50, unique = true)
    private String code;

    @Column(name = "Title", nullable = false, length = 100)
    private String title;

    @Column(name = "Icon", length = 50)
    private String icon;

    /** Image de la vignette — upload réservé à l'administrateur (voir KnowledgeApiController). */
    @Column(name = "ImageUrl", length = 500)
    private String imageUrl;

    @Column(name = "SortOrder", nullable = false)
    private Integer sortOrder;

    /** INBOUND_VOICE | INBOUND_MAIL | CIB | OUTBOUND — voir TeamClassifier.Team. NULL =
     *  catégorie historique, traitée comme INBOUND_VOICE par migration (voir
     *  WorkflowSchemaBootstrap.seedKnowledgeCategoryTeamsIfMissing()). */
    @Column(name = "Team", length = 30)
    private String team;
}
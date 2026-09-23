package com.ecobank.rccportal.model;
import jakarta.persistence.*;
import lombok.*;

/** country = null → contenu valable pour toutes les filiales (procédures, scripts génériques).
 * country renseigné → contenu spécifique à une filiale (tarifs, offres locales). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "KnowledgeArticles", schema = "dbo")
public class KnowledgeArticle extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ArticleId")
    private Integer articleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CategoryId", nullable = false)
    private KnowledgeCategory category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CountryCode")
    private KnowledgeCountry country;

    /** null = article générique valable pour toutes les équipes. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ServiceId")
    private com.ecobank.rccportal.model.RccService service;

    @Column(name = "Title", nullable = false, length = 200)
    private String title;

    @Lob
    @Column(name = "ContentHtml", nullable = false)
    private String contentHtml;

    @Column(name = "Tags", length = 300)
    private String tags;

    @Column(name = "SortOrder", nullable = false)
    private Integer sortOrder;

    @Column(name = "CreatedByUserId")
    private Integer createdByUserId;
}
package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/** Actualités Ecobank affichées en bas de la page d'accueil de tous les portails. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "NewsArticles", schema = "dbo")
public class NewsArticle extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "NewsId")
    private Integer newsId;

    @Column(name = "Title", nullable = false, length = 200)
    private String title;

    @Lob
    @Column(name = "ContentHtml", nullable = false)
    private String contentHtml;

    @Column(name = "ImageUrl", length = 500)
    private String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CreatedByUserId")
    private User createdBy;

    @Column(name = "SortOrder", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}

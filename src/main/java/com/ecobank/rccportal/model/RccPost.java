package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "RccPosts", schema = "dbo")
public class RccPost extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PostId")
    private Integer postId;

    /** null si l'auteur est un compte de service (ex. "Team Communication"). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "AuthorUserId")
    private User author;

    @Column(name = "AuthorLabel", nullable = false, length = 150)
    private String authorLabel;

    @Lob
    @Column(name = "Content", nullable = false)
    private String content;

    @Column(name = "ImageUrl", length = 500)
    private String imageUrl;

    @Column(name = "ViewCount", nullable = false)
    private Integer viewCount;

    @Column(name = "PublishedAt", nullable = false)
    private LocalDateTime publishedAt;

    /**
     * Fin de visibilité dans le fil principal (par défaut 2 semaines après
     * publication) — après cette date le post bascule dans l'historique.
     * Suppression définitive 2 mois après publication (voir PostArchivalJob).
     */
    @Column(name = "ExpiresAt")
    private LocalDateTime expiresAt;
}

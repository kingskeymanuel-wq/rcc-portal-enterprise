package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "RccStories", schema = "dbo")
public class RccStory extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "StoryId")
    private Integer storyId;

    @Column(name = "AuthorLabel", nullable = false, length = 150)
    private String authorLabel;

    @Column(name = "Content", length = 500)
    private String content;

    @Column(name = "ImageUrl", length = 500)
    private String imageUrl;

    @Column(name = "PublishedAt", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "ExpiresAt")
    private LocalDateTime expiresAt;
}

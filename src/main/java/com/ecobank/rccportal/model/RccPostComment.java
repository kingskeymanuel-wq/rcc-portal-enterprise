package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "RccPostComments", schema = "dbo")
public class RccPostComment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CommentId")
    private Integer commentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "PostId", nullable = false)
    private RccPost post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "AuthorUserId")
    private User author;

    @Column(name = "AuthorLabel", nullable = false, length = 150)
    private String authorLabel;

    @Column(name = "Content", nullable = false, length = 1000)
    private String content;
}

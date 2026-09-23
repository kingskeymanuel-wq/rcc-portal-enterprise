package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "RccPostLikes", schema = "dbo",
       uniqueConstraints = @UniqueConstraint(columnNames = {"PostId", "UserId"}))
public class RccPostLike extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RccPostLikeId")
    private Integer rccPostLikeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "PostId", nullable = false)
    private RccPost post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;
}

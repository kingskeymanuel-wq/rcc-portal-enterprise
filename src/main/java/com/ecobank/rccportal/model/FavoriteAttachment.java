package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "FavoriteAttachments", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"UserId", "AttachmentId"})
})
public class FavoriteAttachment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "FavoriteId")
    private Integer favoriteId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "AttachmentId", nullable = false)
    private Attachment attachment;
}

package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "RccCommunityFollows", schema = "dbo",
       uniqueConstraints = @UniqueConstraint(columnNames = {"UserId", "CommunityKey"}))
public class RccCommunityFollow extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RccCommunityFollowId")
    private Integer rccCommunityFollowId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @Column(name = "CommunityKey", nullable = false, length = 50)
    private String communityKey;
}

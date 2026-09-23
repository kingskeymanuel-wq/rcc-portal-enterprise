package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "ConversationParticipants", schema = "dbo",
       uniqueConstraints = @UniqueConstraint(columnNames = {"ConversationId", "UserId"}))
public class ConversationParticipant extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ParticipantId")
    private Integer participantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ConversationId", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    /** Dernier instant où l'utilisateur a consulté la conversation — sert au compteur de non-lus. */
    @Column(name = "LastReadAt")
    private LocalDateTime lastReadAt;
}

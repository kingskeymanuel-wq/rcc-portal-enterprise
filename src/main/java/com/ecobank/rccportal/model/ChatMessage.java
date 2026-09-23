package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "ChatMessages", schema = "dbo")
public class ChatMessage extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "MessageId")
    private Integer messageId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ConversationId", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "SenderUserId", nullable = false)
    private User sender;

    @Lob
    @Column(name = "Content", nullable = false)
    private String content;

    /** Photo/vidéo jointe — uploadée depuis l'ordinateur, comme pour MON RCC. */
    @Column(name = "MediaUrl", length = 500)
    private String mediaUrl;

    /** Message éphémère — une fois cette date passée, le message ne doit plus être affiché. */
    @Column(name = "ExpiresAt")
    private LocalDateTime expiresAt;

    @Column(name = "SentAt", nullable = false)
    private LocalDateTime sentAt;
}

package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "Conversations", schema = "dbo")
public class Conversation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ConversationId")
    private Integer conversationId;

    /** 'DM' (exactement 2 participants) ou 'GROUP'. */
    @Column(name = "Type", nullable = false, length = 10)
    private String type;

    /** Nom du groupe — null pour une conversation DM (affichage résolu côté service). */
    @Column(name = "Name", length = 150)
    private String name;
}

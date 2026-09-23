package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Pièce jointe générique (association polymorphe EntityType/EntityId — voir
 * db/rcc-full-schema/01_schema.sql). Pas de @ManyToOne vers la cible : JPA ne
 * modélise pas nativement le polymorphisme par table générique, la résolution
 * se fait au niveau service (AttachmentService), pas ici.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "Attachments", schema = "dbo")
public class Attachment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AttachmentId")
    private Integer attachmentId;

    /** 'Procedure' | 'AgentDossier' | 'MailTemplate' ... */
    @Column(name = "EntityType", nullable = false, length = 50)
    private String entityType;

    @Column(name = "EntityId", nullable = false)
    private Integer entityId;

    @Column(name = "FileName", nullable = false, length = 260)
    private String fileName;

    @Column(name = "MimeType", length = 100)
    private String mimeType;

    @Column(name = "StorageUrl", nullable = false, length = 500)
    private String storageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UploadedByUserId")
    private User uploadedBy;
}

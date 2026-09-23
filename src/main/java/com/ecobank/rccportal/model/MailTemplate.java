package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "MailTemplates", schema = "dbo")
public class MailTemplate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TemplateId")
    private Integer templateId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CategoryId", nullable = false)
    private MailTemplateCategory category;

    @Column(name = "Subject", nullable = false, length = 300)
    private String subject;

    @Lob
    @Column(name = "Body", nullable = false)
    private String body;

    /** 'person' | 'service' */
    @Column(name = "RecipientType", nullable = false, length = 20)
    private String recipientType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "RecipientGroupId")
    private MailRecipientGroup recipientGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CreatedByUserId")
    private User createdBy;

    @Column(name = "IsSystemTemplate", nullable = false)
    private Boolean isSystemTemplate;
}

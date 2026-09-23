package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Certificat de formation (modèle EduFun) : demandé par l'agent quand un parcours programmé est
 * terminé (SourceType FORMATION) ou une évaluation notée réussie (SourceType COURSE), puis
 * validé (ISSUED, numéro vérifiable), refusé (REJECTED) ou révoqué (REVOKED) par QA.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "TrainingCertificates", schema = "dbo")
public class TrainingCertificate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CertificateId")
    private Long id;

    /** Attribué à la validation QA uniquement (ex. RCC-2026-7K3F9Q). */
    @Column(name = "CertificateNumber", length = 40)
    private String certificateNumber;

    @Column(name = "UserId", nullable = false)
    private Long userId;

    /** FORMATION (TrainingFormation) ou COURSE (Course STANDARD réussi). */
    @Column(name = "SourceType", length = 20, nullable = false)
    private String sourceType;

    @Column(name = "SourceId", nullable = false)
    private Integer sourceId;

    @Column(name = "Title", length = 250, nullable = false)
    private String title;

    @Column(name = "Score")
    private Integer score;

    /** PENDING, ISSUED, REJECTED, REVOKED. */
    @Builder.Default
    @Column(name = "Status", length = 20, nullable = false)
    private String status = "PENDING";

    @Column(name = "RequestedAt", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "DecidedAt")
    private LocalDateTime decidedAt;

    @Column(name = "DecidedBy", length = 150)
    private String decidedBy;

    @Column(name = "DecisionNote", length = 500)
    private String decisionNote;
}

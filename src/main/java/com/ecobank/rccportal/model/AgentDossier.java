package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Dossier semi-structuré (CSV import ou capture IA). Payload en JSON (colonnes
 * variables selon la source — voir commentaire de la table SQL). ⚠️ Peut contenir
 * des données client bancaires : accès à restreindre par rôle côté service.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "AgentDossiers", schema = "dbo")
public class AgentDossier extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DossierId")
    private Integer dossierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LinkedUserId")
    private User linkedUser;

    @Column(name = "Source", nullable = false, length = 100)
    private String source;

    @Lob
    @Column(name = "Payload", nullable = false)
    private String payload;
}

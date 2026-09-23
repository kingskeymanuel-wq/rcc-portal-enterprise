package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Journal d'audit — traçabilité obligatoire pour une banque (voir API 6 de l'architecture
 * cible : "Audit et Traçabilité"). Chaque action significative (import Excel, suppression
 * d'un import...) y est enregistrée, jamais modifiée ni supprimée après coup — c'est la
 * garantie qu'aucune donnée ajoutée/modifiée en base ne peut disparaître sans laisser de trace.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "ActionAuditLogs", schema = "dbo")
public class ActionAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AuditLogId")
    private Integer auditLogId;

    @Column(name = "Username", nullable = false, length = 100)
    private String username;

    /** IMPORT_EXCEL_KPI | IMPORT_EXCEL_SCHEDULE | DELETE_IMPORT_BATCH | ... */
    @Column(name = "Action", nullable = false, length = 50)
    private String action;

    /** Détail lisible — nom du fichier, nombre de lignes, résultat de complétude, etc. */
    @Column(name = "Details", length = 2000)
    private String details;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

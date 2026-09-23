package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Vente enregistrée par un conseiller Outbound (télévendeur) — voir SalesController.
 * Visible par l'agent lui-même, son Team Leader (LedTeam=OUTBOUND) et QA/Admin/Superviseur.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "SalesRecords", schema = "dbo")
public class SalesRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "SaleId")
    private Integer saleId;

    @Column(name = "AgentUserId", nullable = false)
    private Long agentUserId;

    @Column(name = "ProductName", nullable = false, length = 150)
    private String productName;

    @Column(name = "ClientName", length = 200)
    private String clientName;

    @Column(name = "ClientPhone", length = 50)
    private String clientPhone;

    @Column(name = "Amount")
    private Double amount;

    @Column(name = "SaleDate", nullable = false)
    private LocalDate saleDate;

    /** PENDING (en cours de finalisation) | CONFIRMED | CANCELLED */
    @Column(name = "Status", nullable = false, length = 20)
    @Builder.Default
    private String status = "CONFIRMED";

    @Column(name = "Notes", length = 1000)
    private String notes;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

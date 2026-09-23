package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Rendez-vous client pris par un conseiller Outbound — voir AppointmentController.
 * Visible par l'agent lui-même, son Team Leader (LedTeam=OUTBOUND) et QA/Admin/Superviseur.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "Appointments", schema = "dbo")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AppointmentId")
    private Integer appointmentId;

    @Column(name = "AgentUserId", nullable = false)
    private Long agentUserId;

    @Column(name = "ClientName", nullable = false, length = 200)
    private String clientName;

    @Column(name = "ClientPhone", length = 50)
    private String clientPhone;

    @Column(name = "Purpose", length = 300)
    private String purpose;

    @Column(name = "ScheduledAt", nullable = false)
    private LocalDateTime scheduledAt;

    /** PLANNED | DONE | NO_SHOW | CANCELLED */
    @Column(name = "Status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PLANNED";

    @Column(name = "Notes", length = 1000)
    private String notes;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

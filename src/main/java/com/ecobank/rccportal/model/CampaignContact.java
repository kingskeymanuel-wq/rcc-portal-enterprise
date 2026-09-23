package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Contact à appeler dans le cadre d'une campagne Outbound. Le numéro de compte n'est
 * JAMAIS stocké en clair — masqué dès l'import (voir CampaignService.maskAccountNumber),
 * pour une question de confidentialité (voir demande utilisateur).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "CampaignContacts", schema = "dbo")
public class CampaignContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ContactId")
    private Integer contactId;

    @Column(name = "CampaignId", nullable = false)
    private Integer campaignId;

    /** Null tant que le Team Leader ne l'a pas affecté à un agent. */
    @Column(name = "AgentUserId")
    private Long agentUserId;

    @Column(name = "ClientName", nullable = false, length = 200)
    private String clientName;

    @Column(name = "ClientPhone", length = 50)
    private String clientPhone;

    /** Toujours déjà masqué au moment de l'enregistrement (ex. "****1234") — jamais le numéro complet. */
    @Column(name = "MaskedAccountNumber", length = 30)
    private String maskedAccountNumber;

    /** PENDING (pas encore appelé) | GREEN (interaction réussie) | RED (pas d'interaction) | YELLOW (RDV pris) */
    @Column(name = "CallStatus", nullable = false, length = 20)
    @Builder.Default
    private String callStatus = "PENDING";

    @Column(name = "Notes", length = 1000)
    private String notes;

    /** Réponses aux questions dynamiques de la campagne (voir Campaign.fieldsJson) — JSON
     *  {"champId": "valeur", ...}. Distinct de Notes, qui reste le commentaire libre agent. */
    @Column(name = "AnswersJson", length = 4000)
    private String answersJson;

    /** Toute colonne du fichier importé qui ne correspond ni au nom, au téléphone, au compte,
     *  à l'agent, ni à une question du modèle de campagne — JSON {"En-tête original": "valeur"}.
     *  Garantit qu'aucune donnée du fichier source n'est perdue à l'import, quel que soit le
     *  type de fichier d'appel (voir CampaignService.readContactsFile) : affichée en lecture
     *  seule dans la fiche contact, jamais éditable par l'agent (contrairement à AnswersJson). */
    @Column(name = "ExtraDataJson", length = 4000)
    private String extraDataJson;

    @Column(name = "LastCalledAt")
    private LocalDateTime lastCalledAt;

    /** RDV créé depuis ce contact (statut YELLOW), pour que le Team Leader puisse le retrouver directement. */
    @Column(name = "AppointmentId")
    private Integer appointmentId;

    @Column(name = "CreatedAt", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

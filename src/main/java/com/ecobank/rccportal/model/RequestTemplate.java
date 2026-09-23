package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Modèle de demande réutilisable — accélère la saisie pour les motifs récurrents
 * (ex. "Congé maladie", "Changement de casque standard"). Géré par QA/Admin,
 * sélectionnable par tout agent au moment de créer une nouvelle demande Workflow.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "RequestTemplates", schema = "dbo")
public class RequestTemplate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TemplateId")
    private Integer templateId;

    @Column(name = "Name", nullable = false, length = 150)
    private String name;

    /** "LEAVE" | "PROCEDURE_CHANGE" | "ACCESS" */
    @Column(name = "Type", nullable = false, length = 30)
    private String type;

    @Column(name = "DefaultTitle", nullable = false, length = 200)
    private String defaultTitle;

    @Column(name = "DefaultDetails", length = 2000)
    private String defaultDetails;

    /** "QA" | "ADMIN" — équipe pré-sélectionnée, l'agent peut la changer. */
    @Column(name = "DefaultAssignedTeam", length = 10)
    private String defaultAssignedTeam;

    @Column(name = "Active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "CreatedByUserId")
    private Long createdByUserId;
}

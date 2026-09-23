package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Un cours de formation, créé par QA/admin. Deux types : STANDARD (questionnaire
 * à choix multiples, évaluation métier avec bonne/mauvaise réponse) ou
 * SELF_ASSESSMENT (auto-diagnostic à échelle 1-5, pas de bonne/mauvaise réponse —
 * voir CourseQuestion). "mandatory" signale qu'un agent doit le valider.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "Courses", schema = "dbo")
public class Course extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CourseId")
    private Integer courseId;

    @Column(name = "Title", nullable = false, length = 200)
    private String title;

    @Column(name = "Description", length = 1000)
    private String description;

    /** ex. COMPTE, TRANSFERT, CARTE ATM... ou "Général" — même logique que TrainingFormation.category, pour un affichage en grille façon Knowledge Base. */
    @Column(name = "Category", length = 100)
    private String category;

    @Column(name = "Content")
    private String content;

    /** "STANDARD" | "SELF_ASSESSMENT" */
    @Column(name = "Type", nullable = false, length = 20)
    private String type;

    @Column(name = "Mandatory", nullable = false)
    private Boolean mandatory;

    @Column(name = "VideoUrl", length = 500)
    private String videoUrl;

    /** Fichier joint (PDF, Word...) importé pour ce cours — distinct du lien vidéo. */
    @Column(name = "FileUrl", length = 500)
    private String fileUrl;

    @Column(name = "FileName", length = 255)
    private String fileName;

    /** Vignette affichée sur le Centre de Formation — upload réservé à l'administrateur. */
    @Column(name = "ImageUrl", length = 500)
    private String imageUrl;

    /** @deprecated conservé pour compatibilité de schéma — plus utilisé pour le filtrage
     *  de visibilité (voir CourseService.filterVisible), remplacé par {@link #team}. */
    @Deprecated
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ServiceId")
    private RccService service;

    /** Équipe ciblée (référentiel de l'onglet Shift, dbo.Teams) — null = cours générique,
     * assigné à toutes les équipes. Un agent voit un cours si team == null ou si
     * team.code équivaut (insensible à la casse) à son User.activity. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TeamId")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CreatedByUserId")
    private User createdBy;
}
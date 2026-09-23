package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Leçon (module) d'une formation. contentHtml = texte à faire défiler (le scroll
 * jusqu'en bas est mesuré) ; videoUrl = vidéo optionnelle dont la vitesse de
 * lecture est plafonnée à formation.videoMaxPlaybackRate côté lecteur.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "TrainingLessons", schema = "dbo")
public class TrainingLesson extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LessonId")
    private Integer lessonId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "FormationId", nullable = false)
    private TrainingFormation formation;

    @Column(name = "Title", nullable = false, length = 200)
    private String title;

    @Lob
    @Column(name = "ContentHtml")
    private String contentHtml;

    @Column(name = "VideoUrl", length = 500)
    private String videoUrl;

    @Column(name = "OrderIndex", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;

    @Column(name = "EstimatedMinutes")
    private Integer estimatedMinutes;
}

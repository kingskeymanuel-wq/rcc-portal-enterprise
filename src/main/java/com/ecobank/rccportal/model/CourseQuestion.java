package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Une question d'un cours. Pour un cours STANDARD : questionnaire à choix
 * multiples (optionsJson = liste JSON de libellés, correctOptionIndex = bonne
 * réponse). Pour un cours SELF_ASSESSMENT : échelle 1-5, optionsJson et
 * correctOptionIndex restent vides (pas de bonne/mauvaise réponse).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "CourseQuestions", schema = "dbo")
public class CourseQuestion extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "QuestionId")
    private Integer questionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CourseId", nullable = false)
    private Course course;

    @Column(name = "QuestionText", nullable = false, length = 1000)
    private String questionText;

    @Column(name = "QuestionNumber", nullable = false)
    private Integer questionNumber;

    /** Liste JSON de libellés — uniquement pour les cours STANDARD (choix multiples). */
    @Column(name = "OptionsJson", length = 2000)
    private String optionsJson;

    /** Index (0-based) de la bonne réponse dans optionsJson — uniquement pour STANDARD. */
    @Column(name = "CorrectOptionIndex")
    private Integer correctOptionIndex;
}
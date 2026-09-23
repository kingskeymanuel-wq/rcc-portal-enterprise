package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Banque de questions centrale et enrichie — indépendante d'un cours précis.
 * Alimente l'onglet "Questions" (recherche/filtre par thème, difficulté, type)
 * et sert de réservoir commun aux futurs jeux (quiz-jeux type "Qui veut gagner
 * des millions", JoliGo, etc.) : un jeu pioche par thème/difficulté dans cette
 * même table plutôt que de dupliquer des questions.
 *
 * type : MCQ (choix unique parmi optionsJson) | TRUE_FALSE | MULTI_SELECT
 * (plusieurs bonnes réponses possibles, correctIndexesJson = liste d'index).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "QuizQuestions", schema = "dbo")
public class QuizQuestion extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "QuestionId")
    private Integer questionId;

    @Column(name = "QuestionText", nullable = false, length = 1000)
    private String questionText;

    /** MCQ | TRUE_FALSE | MULTI_SELECT */
    @Column(name = "Type", nullable = false, length = 20)
    @Builder.Default
    private String type = "MCQ";

    /** EASY | MEDIUM | HARD | EXPERT */
    @Column(name = "Difficulty", nullable = false, length = 20)
    @Builder.Default
    private String difficulty = "MEDIUM";

    /** Thème (aligné sur les catégories Base de connaissance : COMPTE, TRANSFERT, CARTE ATM...
     *  ou "Actualité Ecobank" / "Culture générale Ecobank" pour les questions des jeux). */
    @Column(name = "Category", length = 100)
    private String category;

    /** Liste JSON de libellés (options de réponse). */
    @Lob
    @Column(name = "OptionsJson")
    private String optionsJson;

    /** Index (0-based) de la bonne réponse — MCQ / TRUE_FALSE. */
    @Column(name = "CorrectOptionIndex")
    private Integer correctOptionIndex;

    /** Liste JSON d'index — uniquement pour MULTI_SELECT. */
    @Column(name = "CorrectIndexesJson", length = 500)
    private String correctIndexesJson;

    /** Explication affichée après la réponse (pédagogie — pourquoi c'est vrai/faux). */
    @Lob
    @Column(name = "Explanation")
    private String explanation;

    @Column(name = "ImageUrl", length = 500)
    private String imageUrl;

    @Column(name = "VideoUrl", length = 500)
    private String videoUrl;

    /** Tags libres séparés par virgule, pour la recherche (ex. "MMH,cashxpress,2026"). */
    @Column(name = "Tags", length = 300)
    private String tags;

    @Column(name = "Points", nullable = false)
    @Builder.Default
    private Integer points = 10;

    /** Temps limite suggéré en secondes (utilisé par les jeux type "Qui veut gagner des millions"). */
    @Column(name = "TimeLimitSeconds")
    private Integer timeLimitSeconds;

    @Column(name = "Active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "CreatedByUserId")
    private Long createdByUserId;

    @Column(name = "UsageCount", nullable = false)
    @Builder.Default
    private Integer usageCount = 0;

    @Column(name = "CorrectAnswerCount", nullable = false)
    @Builder.Default
    private Integer correctAnswerCount = 0;
}

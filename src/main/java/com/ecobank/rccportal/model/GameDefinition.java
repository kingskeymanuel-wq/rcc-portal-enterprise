package com.ecobank.rccportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Définition d'un jeu du "Centre de jeux" — les 10 jeux sont créés une fois par
 * migration (voir 007_add_games.sql) puis éditables par QA/Admin (titre,
 * description, statut actif, paramètres). Le "mechanic" (moteur de jeu) n'est
 * pas éditable — c'est du code (voir games.js) — mais son contenu (thème,
 * nombre de questions, temps) l'est via configJson.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(name = "GameDefinitions", schema = "dbo")
public class GameDefinition extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "GameId")
    private Integer gameId;

    /** Identifiant stable utilisé par le frontend pour choisir le moteur (ex. "quiz-eclair"). */
    @Column(name = "GameKey", nullable = false, unique = true, length = 50)
    private String gameKey;

    /** MCQ_STANDARD | MCQ_LADDER | TRUE_FALSE_RAPID | WHEEL | WORD_GUESS | MCQ_DUEL | MEMORY | HANGMAN | MCQ_SURVIVAL | PROCESS_ORDER */
    @Column(name = "Mechanic", nullable = false, length = 30)
    private String mechanic;

    @Column(name = "Title", nullable = false, length = 150)
    private String title;

    @Column(name = "Description", length = 500)
    private String description;

    @Column(name = "Icon", length = 50)
    private String icon;

    @Column(name = "ColorFrom", length = 20)
    private String colorFrom;

    @Column(name = "ColorTo", length = 20)
    private String colorTo;

    /** JSON libre : {"questionCount":10,"timeLimitSeconds":60,"category":null,"difficulty":null} */
    @Lob
    @Column(name = "ConfigJson")
    private String configJson;

    @Column(name = "SortOrder", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "Active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * Session d'évaluation en cours (jeux à QCM uniquement) — un agent qui a déjà terminé
     * ses 2 tentatives pour cette valeur ne peut plus rejouer, il revoit son résultat final
     * (voir GameService.evaluationStatus()). QA/Admin incrémente cette valeur
     * (startNewEvaluationRound()) pour rouvrir une nouvelle session à tout le monde.
     */
    @Column(name = "EvaluationRound", nullable = false)
    @Builder.Default
    private Integer evaluationRound = 1;

    /** INBOUND_VOICE | INBOUND_MAIL | CIB | OUTBOUND — voir TeamClassifier.Team. NULL =
     *  jeu générique, ouvert à toutes les équipes (comportement historique inchangé). */
    @Column(name = "TargetTeam", length = 30)
    private String targetTeam;
}

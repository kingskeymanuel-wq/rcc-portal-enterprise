package com.ecobank.rccportal.dto;

import java.util.List;

/** Ne jamais inclure correctOptionIndex/correctIndexes/explanation ici — c'est
 *  exactement ce qu'un agent en train de répondre ne doit pas recevoir avant
 *  d'avoir soumis sa réponse (voir QuizQuestionService.toPlayResponse). */
public record QuizQuestionPlayResponse(
        Integer questionId,
        String questionText,
        String type,
        String difficulty,
        String category,
        List<String> options,
        String imageUrl,
        String videoUrl,
        Integer points,
        Integer timeLimitSeconds
) {}

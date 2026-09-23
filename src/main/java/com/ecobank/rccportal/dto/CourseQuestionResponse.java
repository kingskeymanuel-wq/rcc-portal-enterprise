package com.ecobank.rccportal.dto;

import java.util.List;

/** correctOptionIndex n'est renvoyé que côté QA/admin (voir CourseService) — jamais à un agent en train de répondre. */
public record CourseQuestionResponse(
        Integer questionId, String questionText, Integer questionNumber,
        List<String> options, Integer correctOptionIndex) {
}
package com.ecobank.rccportal.dto;

import java.util.List;

/** options et correctOptionIndex uniquement pour un cours STANDARD ; laisser vides pour un SELF_ASSESSMENT. */
public record CreateCourseQuestionRequest(String questionText, List<String> options, Integer correctOptionIndex) {
}
package com.ecobank.rccportal.dto;

import java.util.Map;

/** answers : clé = questionId (en String), valeur = index choisi (STANDARD) ou note 1-5 (SELF_ASSESSMENT). */
public record SubmitCourseAttemptRequest(Map<String, Integer> answers) {
}
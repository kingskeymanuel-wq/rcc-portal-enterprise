package com.ecobank.rccportal.dto;

public record RalphResultItem(
        String sourceType, // "ARTICLE" | "PROCEDURE" | "COURSE" | "QUIZ"
        Integer id,
        String title,
        String snippet
) {
}

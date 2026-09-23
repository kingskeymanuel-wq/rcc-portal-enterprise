package com.ecobank.rccportal.dto;

/** type : "STANDARD" ou "SELF_ASSESSMENT". teamCode optionnel (code d'équipe, référentiel de
 * l'onglet Shift) — vide/absent = cours générique pour toutes les équipes. category optionnel
 * (ex. COMPTE, TRANSFERT...) — regroupe les fiches de cours en rubriques façon Knowledge Base. */
public record CreateCourseRequest(
        String title, String description, String content, String type, Boolean mandatory, String videoUrl,
        String teamCode, String category) {
}

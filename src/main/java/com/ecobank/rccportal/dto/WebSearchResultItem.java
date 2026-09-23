package com.ecobank.rccportal.dto;

/**
 * Un résultat de la recherche web de repli (Bing) — utilisé par RAF
 * uniquement quand la base de connaissances interne ne contient pas de
 * réponse pertinente. Toujours affiché avec sa source pour que
 * l'utilisateur sache qu'il s'agit d'une information externe, pas d'une
 * procédure Ecobank validée.
 */
public record WebSearchResultItem(
        String title,
        String snippet,
        String url
) {
}

package com.ecobank.rccportal.dto;

/**
 * Bouton de relance proposé par RAF sous sa réponse. {@code command} (optionnel) = action
 * déterministe renvoyée telle quelle à /api/ralph/ask (ex. « raf:proc:12:step:1 ») ; sinon
 * {@code query} est reposé comme une nouvelle question.
 */
public record RafSuggestion(String label, String query, String command) {
}

package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.MailFieldSuggestionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Suggestions de réponse pour les champs des masques de mail (tous les masques, y compris ceux créés par les agents). */
@RestController
@RequestMapping("/api/mail-templates")
public class MailFieldSuggestionController {

    private final MailFieldSuggestionService suggestions;

    public MailFieldSuggestionController(MailFieldSuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    @GetMapping("/{id}/suggestions")
    public Map<String, MailFieldSuggestionService.FieldSuggestion> forTemplate(@PathVariable Integer id,
                                                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        return suggestions.suggestionsForTemplate(requester, id);
    }

    /** Mail copié / envoyé : les réponses non personnelles enrichissent les suggestions des autres agents. */
    @PostMapping("/{id}/used")
    public Map<String, Integer> used(@PathVariable Integer id, @RequestBody UsedRequest body, @AuthenticationPrincipal AuthenticatedUser requester) {
        return Map.of("remembered", suggestions.remember(requester, id, body == null ? Map.of() : body.values()));
    }

    /** Création d'un masque : champs proposés selon le sujet (demande de coordonnées, carte, réclamation…). */
    @PostMapping("/suggest-fields")
    public List<MailFieldSuggestionService.SuggestedField> suggestFields(@RequestBody SuggestFieldsRequest body) {
        return suggestions.suggestFields(body == null ? "" : body.subject(), body == null ? "" : body.body());
    }

    public record UsedRequest(Map<String, String> values) {}

    public record SuggestFieldsRequest(String subject, String body) {}
}

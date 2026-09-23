package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.SpellCheckResponse;
import com.ecobank.rccportal.service.LocalSpellCheckClient;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Correcteur orthographe/grammaire — 100% local (LanguageTool), aucune dépendance IA/LLM.
 * Ouvert à tout utilisateur connecté, comme le Traducteur (/api/ralph) et Ralph : aucune donnée
 * sensible, juste de la correction de texte.
 */
@RestController
@RequestMapping("/api/spellcheck")
public class SpellCheckController {

    private final LocalSpellCheckClient spellCheckClient;

    public SpellCheckController(LocalSpellCheckClient spellCheckClient) {
        this.spellCheckClient = spellCheckClient;
    }

    @PostMapping("/check")
    public SpellCheckResponse check(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        String lang = body.getOrDefault("lang", "fr");
        if (text == null) {
            throw ApiException.badRequest("Le champ 'text' est requis.");
        }

        var result = spellCheckClient.check(text, lang);
        List<SpellCheckResponse.IssueDto> issues = result.issues().stream()
                .map(i -> new SpellCheckResponse.IssueDto(
                        i.message(), i.shortMessage(), i.offset(), i.length(),
                        i.suggestions(), i.ruleId(), i.category()))
                .collect(Collectors.toList());

        return new SpellCheckResponse(result.language(), issues);
    }

    @GetMapping("/languages")
    public List<Map<String, String>> languages() {
        return List.of(
                Map.of("code", "fr", "label", "Français"),
                Map.of("code", "en", "label", "Anglais")
        );
    }
}

package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.RalphSearchResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.RalphSearchService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Ouvert à tout utilisateur connecté — Ralph aide tout le monde à retrouver la bonne procédure. */
@RestController
@RequestMapping("/api/ralph")
public class RalphSearchController {

    private final RalphSearchService ralphSearchService;

    public RalphSearchController(RalphSearchService ralphSearchService) {
        this.ralphSearchService = ralphSearchService;
    }

    @GetMapping("/search")
    public RalphSearchResponse search(@RequestParam String keyword) {
        return ralphSearchService.search(keyword);
    }

    /** Version conversationnelle — synthèse IA à partir des mêmes extraits, avec repli automatique sur /search. */
    @GetMapping("/ask")
    public RalphSearchResponse ask(@RequestParam String keyword, @RequestParam(required = false) String lang,
                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        return ralphSearchService.ask(keyword, requester != null ? requester.username() : null, lang);
    }

    /** Traduction pure d'un texte libre — bouton "Traduire" du widget RAF, et Traducteur dédié
     *  (/translator). sourceLang optionnel ("auto" par défaut — détection automatique). */
    @PostMapping("/translate")
    public java.util.Map<String, String> translate(@RequestBody java.util.Map<String, String> body) {
        var result = ralphSearchService.translateDetailed(body.get("text"), body.get("sourceLang"), body.get("targetLang"));
        return java.util.Map.of("translated", result.translatedText(), "detectedSourceLang", result.detectedSourceLang());
    }

    /** Diagnostic réseau du Traducteur — teste chaque source indépendamment. Voir
     *  RalphSearchService.diagnoseTranslationSources(). Ouvert à tout utilisateur connecté,
     *  comme le reste de ce contrôleur — aucune donnée sensible, juste un test de connectivité. */
    @GetMapping("/translate/diagnose")
    public java.util.List<java.util.Map<String, String>> diagnoseTranslate() {
        return ralphSearchService.diagnoseTranslationSources();
    }

    /** Efface le fil de conversation courant de l'agent — repart d'une conversation neuve avec RAF. */
    @PostMapping("/reset-conversation")
    public void resetConversation(@AuthenticationPrincipal AuthenticatedUser requester) {
        if (requester != null) {
            ralphSearchService.resetConversation(requester.username());
        }
    }

    /**
     * RAF lit un fichier (Excel, PDF, Word, CSV) et en tire une analyse — réservé à l'IT
     * (Admin) : c'est une capacité d'analyse de données et de stratégie, pas un outil d'appel
     * pour les conseillers clients.
     */
    @PostMapping(value = "/analyze-file", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public java.util.Map<String, String> analyzeFile(@RequestParam("file") MultipartFile file,
                                                       @RequestParam(required = false) String question,
                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        if (requester == null || !"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("L'analyse de fichier par RAF est réservée à l'IT.");
        }
        return java.util.Map.of("answer", ralphSearchService.analyzeFile(file, question));
    }
}

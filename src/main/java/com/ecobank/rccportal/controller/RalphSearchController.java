package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.RalphSearchResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.RalphSearchService;
import com.ecobank.rccportal.service.TranslationService;
import com.ecobank.rccportal.service.WebSearchClient;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Ouvert à tout utilisateur connecté — Ralph aide tout le monde à retrouver la bonne procédure. */
@RestController
@RequestMapping("/api/ralph")
public class RalphSearchController {

    private final RalphSearchService ralphSearchService;
    private final TranslationService translationService;
    private final WebSearchClient webSearchClient;
    private final com.ecobank.rccportal.raf.RafOrchestrator rafOrchestrator;
    private final com.ecobank.rccportal.raf.RafGapLog rafGapLog;

    public RalphSearchController(RalphSearchService ralphSearchService, TranslationService translationService,
                                 WebSearchClient webSearchClient, com.ecobank.rccportal.raf.RafOrchestrator rafOrchestrator,
                                 com.ecobank.rccportal.raf.RafGapLog rafGapLog) {
        this.ralphSearchService = ralphSearchService;
        this.translationService = translationService;
        this.webSearchClient = webSearchClient;
        this.rafOrchestrator = rafOrchestrator;
        this.rafGapLog = rafGapLog;
    }

    @GetMapping("/search")
    public RalphSearchResponse search(@RequestParam String keyword) {
        return ralphSearchService.search(keyword);
    }

    /**
     * RAF — assistant conversationnel local (agents spécialisés, aucune IA externe).
     * {@code cmd} : action d'un bouton du widget (« raf:proc:12:step:2 »...), sans question.
     */
    @GetMapping("/ask")
    public RalphSearchResponse ask(@RequestParam(required = false) String keyword, @RequestParam(required = false) String lang,
                                   @RequestParam(required = false) String cmd,
                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        return ralphSearchService.ask(keyword, requester != null ? requester.username() : null, lang, cmd);
    }

    /** Même chose en POST — pour les longues saisies (brouillon de mail avec détails). */
    @PostMapping("/ask")
    public RalphSearchResponse askPost(@RequestBody java.util.Map<String, String> body,
                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        return ralphSearchService.ask(body.get("keyword"), requester != null ? requester.username() : null,
                body.get("lang"), body.get("cmd"));
    }

    /** Accueil de RAF avec des exemples cliquables. */
    @GetMapping("/welcome")
    public RalphSearchResponse welcome(@RequestParam(required = false) String lang) {
        return rafOrchestrator.welcome(lang);
    }

    /** Agents de RAF (id, libellé). */
    @GetMapping("/capabilities")
    public java.util.List<java.util.Map<String, String>> capabilities() {
        return rafOrchestrator.capabilities();
    }

    /** Questions restées sans réponse fiable — pour que la QA complète le contenu (QA / IT). */
    @GetMapping("/gaps")
    public java.util.List<com.ecobank.rccportal.raf.RafGapLog.Gap> gaps(@AuthenticationPrincipal AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && requester.service().toLowerCase().replace('_', ' ').contains("quality assurance");
        if (!isAdmin && !isQa) throw ApiException.forbidden("Réservé à la QA et à l'IT.");
        return rafGapLog.recent();
    }

    /** Traduction pure d'un texte libre — bouton "Traduire" du widget RAF, et Traducteur dédié
     *  (/translator). sourceLang optionnel ("auto" par défaut — détection automatique). La
     *  réponse précise le moteur utilisé ("provider") pour que l'agent sache d'où vient le texte. */
    @PostMapping("/translate")
    public java.util.Map<String, String> translate(@RequestBody java.util.Map<String, String> body) {
        var result = translationService.translate(body.get("text"), body.get("sourceLang"), body.get("targetLang"));
        java.util.Map<String, String> response = new java.util.LinkedHashMap<>();
        response.put("translated", result.translatedText());
        response.put("detectedSourceLang", result.detectedSourceLang() != null ? result.detectedSourceLang() : "");
        response.put("provider", result.provider());
        return response;
    }

    /** Sources de traduction actives, dans l'ordre d'essai. */
    @GetMapping("/translate/providers")
    public java.util.List<String> translateProviders() {
        return translationService.activeProviders();
    }

    /** Diagnostic du Traducteur — teste chaque source indépendamment (aucune donnée sensible). */
    @GetMapping("/translate/diagnose")
    public java.util.List<java.util.Map<String, String>> diagnoseTranslate() {
        return translationService.diagnose();
    }

    /** Diagnostic de la recherche web — teste chaque moteur configuré (réservé à l'IT). */
    @GetMapping("/search/diagnose")
    public java.util.List<java.util.Map<String, String>> diagnoseWebSearch(@AuthenticationPrincipal AuthenticatedUser requester) {
        if (requester == null || !"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Le diagnostic de la recherche web est réservé à l'IT.");
        }
        return webSearchClient.diagnose();
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

package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.dto.RalphSearchResponse;
import com.ecobank.rccportal.dto.WebSearchResultItem;
import com.ecobank.rccportal.model.KnowledgeArticle;
import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureStep;
import com.ecobank.rccportal.model.SlaRule;
import com.ecobank.rccportal.repository.KnowledgeArticleRepository;
import com.ecobank.rccportal.repository.ProcedureRepository;
import com.ecobank.rccportal.repository.ProcedureStepRepository;
import com.ecobank.rccportal.repository.SlaRuleRepository;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * "Ralph" (RAF) — assistant conversationnel autonome du RCC Portal.
 *
 * Architecture volontairement simplifiée : le dialogue (ask/askDetails) NE dépend plus ni
 * de la Base de connaissances/des procédures internes, ni de Copilot Studio — RAF répond
 * librement, comme un collègue autonome, via Anthropic Claude uniquement, avec un repli
 * web optionnel pour enrichir la réponse. Aucun grounding ne contraint plus ses réponses.
 *
 * La Base de connaissances et les procédures restent consultables séparément, sans lien
 * avec RAF, via la barre de recherche classique ({@link #search(String)},
 * /api/ralph/search) — c'est un moteur de recherche mots-clés distinct, pas RAF.
 *
 * RAF garde une courte mémoire conversationnelle par agent (voir
 * {@link RafConversationMemoryService}) et sait développer sa dernière réponse sur demande
 * ("donne-moi les détails"), traduire un texte, et répondre dans la langue choisie par
 * l'agent (français par défaut — anglais, portugais, espagnol sur demande).
 */
@Service
public class RalphSearchService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RalphSearchService.class);

    /** Expressions qui, seules ou en tête de question, déclenchent le mode "détails" sur la dernière réponse. */
    private static final List<String> DETAIL_TRIGGERS = List.of(
            "donne moi les details", "donne moi plus de details", "plus de details",
            "detaille", "en detail", "peux tu detailler", "developpe", "explique plus",
            "dis m en plus", "j en veux plus", "plus d informations", "plus d infos"
    );

    /** Civilités reconnues localement, texte déjà normalisé (sans accent, en minuscule) — voir smallTalkFallback(). */
    private static final Set<String> SMALL_TALK_GREETINGS = Set.of(
            "bonjour", "salut", "bonsoir", "coucou", "hello", "hi", "bjr", "slt");
    private static final Set<String> SMALL_TALK_THANKS = Set.of(
            "merci", "merci beaucoup", "merci bien", "top merci");
    private static final Set<String> SMALL_TALK_HOWAREYOU = Set.of(
            "ca va", "comment ca va", "tu vas bien", "comment vas tu");
    private static final Set<String> SMALL_TALK_BYE = Set.of(
            "au revoir", "a plus", "bye", "bonne journee", "a bientot");

    /** Langue de réponse voulue (code ISO — fr/en/pt/es) — voir languageInstruction() et translate(). */
    private static final java.util.Map<String, String> LANGUAGE_NAMES = java.util.Map.of(
            "en", "anglais", "pt", "portugais", "es", "espagnol", "fr", "français"
    );

    /** Utilisés uniquement par {@link #search(String)} (barre de recherche classique) — plus par le dialogue RAF. */
    private final KnowledgeArticleRepository articleRepository;
    private final ProcedureRepository procedureRepository;
    private final ProcedureStepRepository procedureStepRepository;
    private final com.ecobank.rccportal.repository.CourseRepository courseRepository;
    /** Référentiel SLA RCC — seule source de vérité injectée dans le prompt système
     *  de RAF (voir {@link #buildSlaContext()}) pour qu'il ne devine jamais un délai. */
    private final SlaRuleRepository slaRuleRepository;

    private final AnthropicClient anthropicClient;
    private final WebSearchClient webSearchClient;
    private final DataProtectionService dataProtectionService;
    private final RafConversationMemoryService conversationMemoryService;
    private final DocumentTextExtractionService documentTextExtractionService;

    /** Banque de questions d'évaluation QA — leurs bonnes réponses augmentent les connaissances
     *  de RAF (voir {@link #buildQuizKnowledgeContext()}), même principe que buildSlaContext(). */
    private final QuizQuestionService quizQuestionService;

    private final TranslationService translationService;

    public RalphSearchService(KnowledgeArticleRepository articleRepository, ProcedureRepository procedureRepository,
                              ProcedureStepRepository procedureStepRepository,
                              com.ecobank.rccportal.repository.CourseRepository courseRepository,
                              SlaRuleRepository slaRuleRepository,
                              AnthropicClient anthropicClient,
                              WebSearchClient webSearchClient,
                              DataProtectionService dataProtectionService,
                              RafConversationMemoryService conversationMemoryService,
                              DocumentTextExtractionService documentTextExtractionService,
                              QuizQuestionService quizQuestionService,
                              TranslationService translationService) {
        this.articleRepository = articleRepository;
        this.procedureRepository = procedureRepository;
        this.procedureStepRepository = procedureStepRepository;
        this.courseRepository = courseRepository;
        this.slaRuleRepository = slaRuleRepository;
        this.anthropicClient = anthropicClient;
        this.webSearchClient = webSearchClient;
        this.dataProtectionService = dataProtectionService;
        this.conversationMemoryService = conversationMemoryService;
        this.documentTextExtractionService = documentTextExtractionService;
        this.quizQuestionService = quizQuestionService;
        this.translationService = translationService;
    }

    /**
     * Point d'entrée conversationnel de RAF, sans mémoire ni masquage — conservé pour
     * compatibilité (ancien appelant sans identité utilisateur). Préférer
     * {@link #ask(String, String)} dès que l'identité de l'agent est disponible.
     */
    @Transactional(readOnly = true)
    public RalphSearchResponse ask(String question) {
        return ask(question, null);
    }

    /** Compatibilité — français par défaut. Préférer {@link #ask(String, String, String)} pour choisir la langue. */
    @Transactional(readOnly = true)
    public RalphSearchResponse ask(String question, String username) {
        return ask(question, username, null);
    }

    /**
     * Point d'entrée conversationnel de RAF — Anthropic Claude uniquement, avec repli web
     * optionnel. Aucun grounding sur la Base de connaissances/les procédures, aucun appel à
     * Copilot Studio. Ne lève jamais d'exception vers l'appelant : si Anthropic est
     * indisponible ou échoue, réponse locale honnête plutôt qu'un texte inventé.
     *
     * @param lang code ISO de la langue de réponse voulue (fr/en/pt/es) — null ou "fr" = français.
     */
    @Transactional(readOnly = true)
    public RalphSearchResponse ask(String question, String username, String lang) {
        if (question == null || question.isBlank()) {
            throw ApiException.badRequest("La question ne peut pas être vide.");
        }

        // Mode "détails" — développe la dernière réponse RAF de cet agent plutôt que de
        // relancer une recherche à partir d'une question qui n'a de sens qu'en contexte.
        if (isDetailRequest(question)) {
            Deque<RafConversationMemoryService.Turn> history = conversationMemoryService.history(username);
            if (!history.isEmpty()) {
                return askDetails(history.getLast(), username, lang);
            }
            // Pas d'historique disponible : on traite quand même la question telle quelle.
        }

        // Repli web — best-effort, uniquement pour enrichir la réponse de l'IA, jamais pour
        // la contraindre. La question est assainie avant tout envoi externe.
        List<WebSearchResultItem> webResults = List.of();
        boolean usedWeb = false;
        if (webSearchClient.isConfigured()) {
            String sanitizedQuestion = dataProtectionService.sanitize(question);
            webResults = webSearchClient.search(sanitizedQuestion);
            usedWeb = !webResults.isEmpty();
            if (usedWeb) {
                log.info("RAF : repli web pour la question posée par {} ({} résultat(s)).",
                        username != null ? username : "anonyme", webResults.size());
            }
        }

        String source = usedWeb ? "WEB" : "AI";
        List<String> sourcesConsulted = usedWeb ? new ArrayList<>(List.of("Recherche web")) : new ArrayList<>();
        int confidence = usedWeb ? 65 : 70;

        String prompt = dataProtectionService.sanitize(languageInstruction(lang) + buildSynthesisPrompt(question, webResults));

        if (anthropicClient.isConfigured()) {
            try {
                String answer = anthropicClient.chat(synthesisSystemPrompt(), prompt, 900);
                List<String> sourcesWithEngine = withEngine(sourcesConsulted, "Anthropic Claude");
                RalphSearchResponse response = new RalphSearchResponse(answer, List.of(), source, webResults, confidence, sourcesWithEngine);
                conversationMemoryService.record(username, question, answer);
                return response;
            } catch (ApiException e) {
                log.warn("RAF : appel Anthropic Claude échoué, repli sur réponse locale : {}", e.getMessage());
            }
        } else {
            log.debug("RAF : Anthropic Claude non configuré (quality.ai.anthropic-key manquant).");
        }

        // Repli final — Anthropic indisponible, en échec, ou non configuré. Au lieu d'un
        // message d'impasse, RAF interroge maintenant sa propre base locale (Knowledge Base +
        // procédures + banque de questions — même moteur que la barre de recherche
        // "/api/ralph/search") et compose une vraie réponse structurée à partir de ce qu'il
        // trouve. Aucune dépendance à un fournisseur externe pour cette voie.
        String smallTalkReply = smallTalkFallback(question);
        if (smallTalkReply != null) {
            List<String> sourcesSmallTalk = withEngine(sourcesConsulted, "Réponse locale");
            RalphSearchResponse response = new RalphSearchResponse(
                    smallTalkReply, List.of(), source, webResults, confidence, sourcesSmallTalk);
            conversationMemoryService.record(username, question, smallTalkReply);
            return response;
        }

        InternalContext internal = buildInternalContext(question);
        if (!internal.articleMatches().isEmpty() || !internal.stepMatches().isEmpty() || !internal.courseMatches().isEmpty()) {
            String localAnswer = buildExplanation(question, internal.articleMatches(), internal.stepMatches(), internal.courseMatches(), 400);
            List<String> sourcesLocal = withEngine(sourcesConsulted, "Base de connaissances / procédures / formations (recherche locale)");
            int localConfidence = usedWeb ? 55 : 50; // un peu moins qu'une synthèse IA — c'est un extrait, pas une reformulation
            RalphSearchResponse response = new RalphSearchResponse(
                    localAnswer, internal.results(), usedWeb ? "MIXED" : "INTERNAL", webResults, localConfidence, sourcesLocal);
            conversationMemoryService.record(username, question, localAnswer);
            return response;
        }

        if (usedWeb) {
            // Rien en interne, mais la recherche web a des résultats — les présenter
            // directement plutôt que d'abandonner (RAF reste utile même sans IA de synthèse).
            String webAnswer = "Je n'ai rien trouvé d'assez précis dans la Base de connaissances ou les procédures internes, " +
                    "mais voici ce que la recherche web a remonté sur ce sujet — à vérifier avant de t'en servir.";
            List<String> sourcesWebOnly = withEngine(sourcesConsulted, "Recherche web");
            RalphSearchResponse response = new RalphSearchResponse(webAnswer, List.of(), "WEB", webResults, 45, sourcesWebOnly);
            conversationMemoryService.record(username, question, webAnswer);
            return response;
        }

        List<String> sourcesFallback = withEngine(sourcesConsulted, "Aucune source disponible");
        String noAiReply = "**Aucun résultat** pour « " + question + " » — ni dans la Base de connaissances, ni dans les procédures, ni sur le web.\n\n" +
                "💡 Tu peux :\n" +
                "- Reformuler avec des mots-clés plus précis (ex. le nom exact du produit ou de la procédure)\n" +
                "- Consulter directement la **Base de connaissances** ou les **Procédures** via le menu\n" +
                "- Demander « détails » si ta question précédente avait des résultats partiels";
        RalphSearchResponse response = new RalphSearchResponse(noAiReply, List.of(), source, webResults, confidence, sourcesFallback);
        conversationMemoryService.record(username, question, noAiReply);
        return response;
    }

    /** Ajoute le moteur réellement utilisé (celui qui a produit la réponse) à la liste des sources, sans dupliquer la liste de base. */
    private List<String> withEngine(List<String> baseSources, String engineLabel) {
        List<String> result = new ArrayList<>(baseSources);
        result.add(engineLabel);
        return result;
    }

    /**
     * Petites civilités reconnues localement (aucune IA nécessaire) — pour que RAF reste
     * accueillant même quand Anthropic et le repli web sont tous les deux indisponibles.
     * Ne couvre QUE les civilités très courantes ; toute vraie question sans IA disponible
     * reçoit une réponse honnête ("pas d'IA configurée"), jamais un texte inventé.
     */
    private String smallTalkFallback(String question) {
        String normalized = normalize(question);
        if (SMALL_TALK_GREETINGS.contains(normalized)) {
            return "Bonjour ! Je suis RAF, à ton service. 😊 Qu'est-ce que je peux faire pour toi aujourd'hui — " +
                    "une procédure, une info sur un produit, ou autre chose ?";
        }
        if (SMALL_TALK_THANKS.contains(normalized)) {
            return "Avec plaisir ! N'hésite pas si tu as une autre question.";
        }
        if (SMALL_TALK_HOWAREYOU.contains(normalized)) {
            return "Tout va bien de mon côté, merci ! Comment puis-je t'aider ?";
        }
        if (SMALL_TALK_BYE.contains(normalized)) {
            return "À bientôt !";
        }
        return null;
    }

    /** Développe la dernière réponse de l'agent — même principe (Anthropic seul, aucun grounding). */
    private RalphSearchResponse askDetails(RafConversationMemoryService.Turn lastTurn, String username, String lang) {
        List<String> sourcesConsulted = new ArrayList<>();
        int confidence = 70;

        StringBuilder sb = new StringBuilder();
        sb.append("L'agent demande à développer la réponse précédente. Question d'origine :\n")
                .append(lastTurn.question()).append("\n\nRéponse résumée déjà donnée :\n")
                .append(lastTurn.answer()).append("\n\n");
        sb.append("Développe une réponse complète et détaillée, avec des exemples concrets si utile. ")
                .append("Reste cohérent avec ce que tu as déjà répondu, sans inventer de fait présenté comme officiel Ecobank.");

        String prompt = dataProtectionService.sanitize(languageInstruction(lang) + sb);

        if (anthropicClient.isConfigured()) {
            try {
                String answer = anthropicClient.chat(synthesisSystemPrompt(), prompt, 1200);
                conversationMemoryService.record(username, "(détails) " + lastTurn.question(), answer);
                return new RalphSearchResponse(answer, List.of(), "AI", List.of(), confidence, withEngine(sourcesConsulted, "Anthropic Claude"));
            } catch (ApiException e) {
                log.warn("RAF (détails) : appel Anthropic Claude échoué : {}", e.getMessage());
            }
        } else {
            log.debug("RAF (détails) : Anthropic Claude non configuré (quality.ai.anthropic-key manquant).");
        }

        // Repli local — même principe que ask() : on redéveloppe la question d'ORIGINE via la
        // recherche locale, avec un extrait plus long qu'en réponse résumée (l'agent veut du détail).
        InternalContext internal = buildInternalContext(lastTurn.question());
        String reply;
        String sourceLabel;
        if (!internal.articleMatches().isEmpty() || !internal.stepMatches().isEmpty() || !internal.courseMatches().isEmpty()) {
            reply = buildExplanation(lastTurn.question(), internal.articleMatches(), internal.stepMatches(), internal.courseMatches(), 1200);
            sourceLabel = "Base de connaissances / procédures / formations (recherche locale)";
        } else {
            reply = "Je n'ai pas de détail supplémentaire disponible localement pour « " + lastTurn.question() +
                    " ». Essayez de reformuler la question d'origine avec des mots plus précis.";
            sourceLabel = "Aucune source disponible";
        }
        conversationMemoryService.record(username, "(détails) " + lastTurn.question(), reply);
        return new RalphSearchResponse(reply, internal.results(), "INTERNAL", List.of(), 50, withEngine(sourcesConsulted, sourceLabel));
    }

    private boolean isDetailRequest(String question) {
        String normalized = normalize(question);
        return DETAIL_TRIGGERS.stream().anyMatch(normalized::contains);
    }

    private String synthesisSystemPrompt() {
        return "Tu es RAF, un collègue IA autonome et humain au RCC Portal Ecobank — pas un moteur de recherche. " +
                "Réponds avec la même aisance qu'un collègue expérimenté et sympathique : direct, " +
                "naturel, jamais robotique. Tu peux répondre à absolument n'importe quelle question, y compris " +
                "sans rapport avec Ecobank (conseils, rédaction, réflexion, discussion générale) — réponds " +
                "toujours librement à partir de tes propres connaissances, comme le ferait un collègue compétent.\n\n" +
                "Sois proactif dans le dialogue : après une réponse complète, si une question de suivi logique " +
                "aiderait l'utilisateur à avancer (préciser un cas, aller plus loin, explorer un sujet lié), " +
                "propose-la brièvement en une phrase à la fin — sans forcer si ce n'est pas naturel. Ne jamais " +
                "inventer un chiffre ou un fait présenté comme officiel Ecobank sans certitude.\n\n" +
                buildSlaContext() +
                buildQuizKnowledgeContext() +
                "Réponds en français, sauf instruction explicite contraire donnée juste avant la question.";
    }

    /**
     * Sérialise les questions actives de la banque d'évaluation (Q/R vérifiées, créées
     * uniquement par QA/Admin — voir QuizQuestionController) en un bloc de contexte factuel,
     * même principe que buildSlaContext() : RAF s'appuie dessus au lieu d'inventer, ses
     * connaissances augmentent automatiquement à chaque nouvelle question ajoutée à la
     * banque d'évaluation. Retourne une chaîne vide si la banque est vide.
     */
    private String buildQuizKnowledgeContext() {
        List<String> summaries = quizQuestionService.activeQuestionsKnowledgeSummary();
        if (summaries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== Connaissances issues de la banque d'évaluation RCC (Q/R vérifiées par QA) ===\n");
        sb.append("Utilise ces informations comme faits fiables quand elles sont pertinentes pour la question posée :\n");
        for (String line : summaries) {
            sb.append(line).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Sérialise le référentiel SLA RCC actif (table SlaRules, éditable depuis
     * Administration) en un bloc de contexte factuel injecté dans le prompt système.
     * C'est la SEULE source de vérité pour les délais annoncés au client — RAF doit
     * s'appuyer dessus plutôt que d'inventer un chiffre. Retourne une chaîne vide
     * (aucun impact sur le prompt) si la table n'est pas encore peuplée.
     */
    private String buildSlaContext() {
        List<SlaRule> rules = slaRuleRepository.findByIsActiveTrueAndPoleIsNullOrderBySortOrderAscMotifAsc();
        if (rules.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("RÉFÉRENTIEL SLA RCC (délais de traitement officiels — utilise UNIQUEMENT ces valeurs quand on te demande ")
          .append("un délai de traitement ou un SLA ; ne dévie jamais de ce tableau et ne l'invente jamais s'il n'y figure pas) :\n");
        for (SlaRule r : rules) {
            sb.append("- ").append(r.getMotif()).append(" [").append(r.getCategory());
            if (r.getLevel() != null && !r.getLevel().isBlank()) sb.append(", ").append(r.getLevel());
            sb.append("] → SLA : ").append(r.getSlaLabel());
            if (Boolean.TRUE.equals(r.getAutoEscalation())) {
                sb.append(" (une reverse/traitement automatique s'applique d'abord, avant ouverture d'un dossier manuel)");
            }
            if (r.getDestinationService() != null && !r.getDestinationService().isBlank()) {
                sb.append(" — service : ").append(r.getDestinationService());
            }
            if (r.getNotes() != null && !r.getNotes().isBlank()) {
                sb.append(". ").append(r.getNotes());
            }
            sb.append("\n");
        }
        sb.append("Si la question porte sur un motif absent de ce tableau, dis clairement que tu n'as pas ce SLA en ")
          .append("référence plutôt que d'estimer un délai.\n\n");
        return sb.toString();
    }

    /** Instruction de langue préfixée au prompt — vide si français (comportement par défaut inchangé, aucune régression). */
    private String languageInstruction(String lang) {
        if (lang == null || lang.isBlank() || "fr".equalsIgnoreCase(lang)) return "";
        String languageName = LANGUAGE_NAMES.get(lang.toLowerCase());
        if (languageName == null) return ""; // code de langue non reconnu — on ignore plutôt que de deviner
        return "IMPORTANT : réponds intégralement en " + languageName + ", quelle que soit la langue de la question ci-dessous.\n\n";
    }

    /**
     * Traduit un texte libre (détection automatique de la langue source). Conservé pour
     * compatibilité — la logique de traduction vit maintenant dans {@link TranslationService}.
     */
    public String translate(String text, String targetLang) {
        return translationService.translate(text, "auto", targetLang).translatedText();
    }

    /** Construit le prompt utilisateur envoyé à Anthropic Claude — plus de contexte interne Ecobank, juste la question et le web éventuel. */
    private String buildSynthesisPrompt(String question, List<WebSearchResultItem> webResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question posée par un utilisateur du RCC Portal Ecobank :\n").append(question).append("\n\n");

        if (!webResults.isEmpty()) {
            sb.append("=== Résultats web (source externe, à distinguer clairement de toute information Ecobank officielle) ===\n");
            for (WebSearchResultItem item : webResults) {
                sb.append("[").append(item.title()).append("] ").append(item.snippet())
                        .append(" (").append(item.url()).append(")\n");
            }
            sb.append("\n");
        }

        sb.append("Réponds naturellement, comme un collègue. Termine par une question de suivi utile si ça a du sens, sans forcer.");
        return sb.toString();
    }

    /** Efface le fil de conversation courant de l'agent — nouvelle conversation explicite. */
    public void resetConversation(String username) {
        conversationMemoryService.clear(username);
    }

    /**
     * RAF lit un fichier importé (Excel, PDF, Word, CSV) et en tire une analyse — utile pour
     * un fichier de KPI, un export de statistiques, ou tout document à interpréter. Sur
     * demande, propose des pistes de stratégie marketing/management, mais toujours ancré sur
     * le contenu réel du fichier, jamais des chiffres inventés.
     */
    @Transactional(readOnly = true)
    public String analyzeFile(org.springframework.web.multipart.MultipartFile file, String question) {
        String content = documentTextExtractionService.extractForAnalysis(file);
        // Garde-fou supplémentaire côté prompt — extractForAnalysis tronque déjà les très gros
        // tableurs, mais un PDF/Word volumineux n'a pas cette limite en amont.
        String truncated = content.length() > 40000 ? content.substring(0, 40000) + "\n[... contenu tronqué ...]" : content;

        String systemPrompt = "Tu es RAF, l'assistant du RCC Portal Ecobank. On te fournit le contenu réel d'un " +
                "fichier importé par un conseiller ou la QA — analyse-le sérieusement. Base-toi UNIQUEMENT sur " +
                "les données ci-dessous, jamais de chiffre inventé. Si la question porte sur une analyse de " +
                "données, dégage les tendances et chiffres clés visibles. Si on te demande des propositions " +
                "(stratégie marketing, management, amélioration de performance...), reste concret et actionnable, " +
                "et précise que ce sont des pistes à valider par un responsable, pas des décisions prises. " +
                "Réponds en français.\n\nContenu du fichier :\n" + truncated;

        String userPrompt = (question != null && !question.isBlank())
                ? question
                : "Analyse ce fichier : dégage les points clés et propose des pistes concrètes d'amélioration.";

        return anthropicClient.chat(systemPrompt, userPrompt, 900, 90);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Barre de recherche classique — SEUL point d'accès restant à la Base de
    // connaissances / aux procédures. Totalement indépendant de ask()/askDetails()
    // ci-dessus : aucune IA ici, juste un vrai moteur de recherche mots-clés local.
    // ══════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public RalphSearchResponse search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw ApiException.badRequest("keyword is required.");
        }

        if (SearchText.queryTerms(keyword).isEmpty()) {
            return new RalphSearchResponse("Précise un peu plus ta recherche avec des mots plus spécifiques.", List.of());
        }

        InternalContext internal = buildInternalContext(keyword);
        String explanation = buildExplanation(keyword, internal.articleMatches(), internal.stepMatches(), internal.courseMatches(), 400);

        // Barre de recherche autonome — quand les résultats internes sont maigres (0 ou 1),
        // complète avec une vraie recherche web plutôt qu'un simple lien générique, pour que
        // l'agent voie immédiatement s'il existe une information plus large ou plus récente
        // en dehors de l'application. Jamais utilisé pour REMPLACER les résultats internes,
        // seulement pour les compléter — voir docstring de WebSearchResultItem.
        List<WebSearchResultItem> webResults = List.of();
        if (internal.results().size() < 2 && webSearchClient.isConfigured()) {
            webResults = webSearchClient.search(dataProtectionService.sanitize(keyword));
        }
        String source = webResults.isEmpty() ? "INTERNAL" : (internal.results().isEmpty() ? "WEB" : "MIXED");
        List<String> sourcesConsulted = new ArrayList<>(List.of("Base de connaissances", "Procédures internes", "Formations"));
        if (!webResults.isEmpty()) sourcesConsulted.add("Recherche web");

        return new RalphSearchResponse(explanation, internal.results(), source, webResults,
                internal.results().isEmpty() && webResults.isEmpty() ? 0 : 60, sourcesConsulted);
    }

    /**
     * Assemble le contexte (articles + étapes de procédures + formations) pour
     * {@link #search(String)} et le repli local de RAF. Pertinence calculée par
     * {@link SearchText} (accents, pluriels, fautes de frappe, pondération titre/contenu)
     * au lieu de l'ancien {@code contains()} qui faisait matcher « art » dans « carte ».
     */
    private InternalContext buildInternalContext(String question) {
        List<String> terms = SearchText.queryTerms(question);
        if (terms.isEmpty()) {
            return new InternalContext(List.of(), List.of(), List.of(), List.of());
        }
        int termCount = terms.size();

        List<Scored<KnowledgeArticle>> articleMatches = new ArrayList<>();
        for (KnowledgeArticle article : articleRepository.findAll()) {
            SearchText.Match m = SearchText.score(question, terms,
                    SearchText.Field.of(article.getTitle(), 3.0),
                    SearchText.Field.of(article.getTags(), 2.0),
                    SearchText.Field.of(stripHtml(article.getContentHtml()), 1.0));
            if (m.isRelevant(termCount)) articleMatches.add(new Scored<>(article, m.score()));
        }
        articleMatches.sort((a, b) -> Double.compare(b.score, a.score));

        // Toutes les étapes en UNE requête (l'ancienne boucle faisait une requête SQL par procédure).
        List<Procedure> procedures = procedureRepository.findAll();
        Map<Integer, List<ProcedureStep>> stepsByProcedure = procedures.isEmpty() ? Map.of()
                : procedureStepRepository.findByProcedureIn(procedures).stream()
                .collect(java.util.stream.Collectors.groupingBy(st -> st.getProcedure().getProcedureId()));
        List<Scored<ProcedureStepMatch>> stepMatches = new ArrayList<>();
        for (Procedure procedure : procedures) {
            // Une seule entrée par procédure (sa meilleure étape) — évite qu'une procédure
            // longue occupe à elle seule toute la liste de résultats.
            Scored<ProcedureStepMatch> best = null;
            for (ProcedureStep step : stepsByProcedure.getOrDefault(procedure.getProcedureId(), List.of())) {
                if (isPlaceholderStepContent(step.getContent())) continue; // pas encore rédigée par QA
                SearchText.Match m = SearchText.score(question, terms,
                        SearchText.Field.of(procedure.getTitle(), 3.0),
                        SearchText.Field.of(step.getContent(), 1.0));
                if (m.isRelevant(termCount) && (best == null || m.score() > best.score)) {
                    best = new Scored<>(new ProcedureStepMatch(procedure, step), m.score());
                }
            }
            if (best != null) stepMatches.add(best);
        }
        stepMatches.sort((a, b) -> Double.compare(b.score, a.score));

        List<Scored<com.ecobank.rccportal.model.Course>> courseMatches = new ArrayList<>();
        for (com.ecobank.rccportal.model.Course course : courseRepository.findAll()) {
            SearchText.Match m = SearchText.score(question, terms,
                    SearchText.Field.of(course.getTitle(), 3.0),
                    SearchText.Field.of(safe(course.getCategory()), 1.5),
                    SearchText.Field.of(safe(course.getDescription()), 1.0));
            if (m.isRelevant(termCount)) courseMatches.add(new Scored<>(course, m.score()));
        }
        courseMatches.sort((a, b) -> Double.compare(b.score, a.score));

        // Résultats fusionnés et triés par pertinence globale (et non plus « tous les articles
        // puis toutes les formations puis toutes les procédures », qui cachait le meilleur résultat).
        List<Scored<RalphResultItem>> merged = new ArrayList<>();
        articleMatches.stream().limit(5).forEach(m -> merged.add(new Scored<>(new RalphResultItem(
                "ARTICLE", m.value.getArticleId(), m.value.getTitle(),
                SearchText.snippetAround(stripHtml(m.value.getContentHtml()), terms, 180)), m.score)));
        courseMatches.stream().limit(5).forEach(m -> merged.add(new Scored<>(new RalphResultItem(
                "COURSE", m.value.getCourseId(), m.value.getTitle(),
                SearchText.snippetAround(safe(m.value.getDescription()), terms, 180)), m.score)));
        stepMatches.stream().limit(5).forEach(m -> merged.add(new Scored<>(new RalphResultItem(
                "PROCEDURE", m.value.procedure.getProcedureId(),
                m.value.procedure.getTitle() + " — étape " + m.value.step.getStepNumber(),
                SearchText.snippetAround(m.value.step.getContent(), terms, 180)), m.score)));
        merged.sort((a, b) -> Double.compare(b.score, a.score));
        List<RalphResultItem> results = merged.stream().limit(12).map(m -> m.value).toList();

        return new InternalContext(articleMatches, stepMatches, courseMatches, results);
    }

    /** Évite les NullPointerException sur les champs optionnels (Course.description peut être vide). */
    private String safe(String text) {
        return text != null ? text : "";
    }

    private record InternalContext(
            List<Scored<KnowledgeArticle>> articleMatches,
            List<Scored<ProcedureStepMatch>> stepMatches,
            List<Scored<com.ecobank.rccportal.model.Course>> courseMatches,
            List<RalphResultItem> results
    ) {
    }

    /** Vrai si l'étape n'a pas encore été rédigée par la QA — jamais affichée comme une vraie réponse. */
    private boolean isPlaceholderStepContent(String content) {
        return content != null && content.startsWith("En attente de rédaction par QA");
    }

    private String buildExplanation(String keyword, List<Scored<KnowledgeArticle>> articleMatches,
                                     List<Scored<ProcedureStepMatch>> stepMatches,
                                     List<Scored<com.ecobank.rccportal.model.Course>> courseMatches, int snippetLength) {
        int totalMatches = articleMatches.size() + stepMatches.size() + courseMatches.size();

        if (totalMatches == 0) {
            return "**Aucun résultat** pour « " + keyword + " » dans la Base de connaissances, les procédures ou les cours.\n\n" +
                    "💡 Essayez avec des mots plus précis, ou consultez directement :\n" +
                    "- La **Base de connaissances** (menu de gauche)\n" +
                    "- Les **Procédures de traitement** (menu de gauche)\n" +
                    "- La **Formation** (menu de gauche)";
        }

        // Plusieurs résultats potentiellement pertinents — poser une vraie question de
        // clarification (comme le ferait un collègue) plutôt que de choisir silencieusement
        // le premier et ignorer le reste. C'est le cœur de ce qui distingue un assistant d'un
        // simple bloc de texte : il demande avant de supposer ce qu'on cherche vraiment.
        if (totalMatches > 1) {
            StringBuilder sb = new StringBuilder("J'ai trouvé plusieurs résultats pour « ").append(keyword).append(" » — sur lequel veux-tu une info précise ?\n\n");
            int n = 1;
            for (Scored<ProcedureStepMatch> m : stepMatches.stream().limit(3).toList()) {
                sb.append(n++).append(". 📋 Procédure — ").append(m.value.procedure.getTitle()).append("\n");
            }
            for (Scored<KnowledgeArticle> m : articleMatches.stream().limit(3).toList()) {
                sb.append(n++).append(". 📖 ").append(m.value.getTitle()).append("\n");
            }
            for (Scored<com.ecobank.rccportal.model.Course> m : courseMatches.stream().limit(3).toList()) {
                sb.append(n++).append(". 🎓 Formation — ").append(m.value.getTitle()).append("\n");
            }
            sb.append("\n💡 Clique sur celui qui t'intéresse ci-dessous, ou reformule avec le titre exact si aucun ne correspond.");
            return sb.toString();
        }

        // Un seul résultat au total — on peut le développer directement, sans détour.
        StringBuilder sb = new StringBuilder();

        if (!stepMatches.isEmpty()) {
            Procedure topProcedure = stepMatches.get(0).value.procedure;
            sb.append("**📋 Procédure — ").append(topProcedure.getTitle()).append("**\n\n");
            for (ProcedureStep step : procedureStepRepository.findByProcedureOrderByStepNumberAsc(topProcedure)) {
                sb.append(step.getStepNumber()).append(". ").append(step.getContent()).append("\n");
            }
        }

        if (!articleMatches.isEmpty()) {
            KnowledgeArticle topArticle = articleMatches.get(0).value;
            if (sb.length() > 0) sb.append("\n");
            sb.append("**📖 Base de connaissances — ").append(topArticle.getTitle()).append("**\n\n");
            sb.append(snippet(stripHtml(topArticle.getContentHtml()), snippetLength));
        }

        if (!courseMatches.isEmpty()) {
            com.ecobank.rccportal.model.Course topCourse = courseMatches.get(0).value;
            if (sb.length() > 0) sb.append("\n");
            sb.append("**🎓 Formation — ").append(topCourse.getTitle()).append("**\n\n");
            sb.append(snippet(safe(topCourse.getDescription()), snippetLength));
        }

        sb.append("\n\n---\n");
        if (!stepMatches.isEmpty()) {
            sb.append("💡 Dis « détails » pour la procédure complète, ou pose-moi directement ta prochaine question sur ce sujet.");
        } else if (!courseMatches.isEmpty()) {
            sb.append("💡 Tu peux suivre ce cours depuis l'onglet Formation — dis-moi aussi si tu cherches une procédure liée à ce sujet.");
        } else {
            sb.append("💡 Dis « détails » pour un extrait plus complet, ou pose-moi une question plus précise sur ce sujet.");
        }
        return sb.toString();
    }

    private String normalize(String text) {
        if (text == null) return "";
        String withoutAccents = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", ""); // retire les diacritiques (accents) après décomposition Unicode
        return withoutAccents.toLowerCase(Locale.FRENCH)
                .replaceAll("[^\\p{L}\\p{Nd}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String stripHtml(String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private String snippet(String text) {
        return snippet(text, 180);
    }

    private String snippet(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    private static class Scored<T> {
        final T value;
        final double score;
        Scored(T value, double score) { this.value = value; this.score = score; }
    }

    private static class ProcedureStepMatch {
        final Procedure procedure;
        final ProcedureStep step;
        ProcedureStepMatch(Procedure procedure, ProcedureStep step) { this.procedure = procedure; this.step = step; }
    }
}

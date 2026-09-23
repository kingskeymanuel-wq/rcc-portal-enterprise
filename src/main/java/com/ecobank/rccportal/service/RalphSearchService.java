package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.dto.RalphSearchResponse;
import com.ecobank.rccportal.dto.WebSearchResultItem;
import com.ecobank.rccportal.model.KnowledgeArticle;
import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureStep;
import com.ecobank.rccportal.repository.KnowledgeArticleRepository;
import com.ecobank.rccportal.repository.ProcedureRepository;
import com.ecobank.rccportal.repository.ProcedureStepRepository;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Façade de RAF et de la barre de recherche classique.
 *
 * <ul>
 *   <li>{@link #ask} — RAF, assistant conversationnel : délégué à {@link com.ecobank.rccportal.raf.RafOrchestrator}
 *       (agents locaux spécialisés, aucune IA externe dans le chemin de réponse).</li>
 *   <li>{@link #search(String)} — barre de recherche globale (/api/ralph/search) : moteur de
 *       pertinence local, complété par la recherche web quand les résultats internes sont maigres.</li>
 *   <li>{@link #analyzeFile} — analyse de fichier réservée à l'IT (seul usage restant d'Anthropic,
 *       hors du dialogue RAF).</li>
 * </ul>
 */
@Service
public class RalphSearchService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RalphSearchService.class);

    /** Utilisés uniquement par {@link #search(String)} (barre de recherche classique) — plus par le dialogue RAF. */
    private final KnowledgeArticleRepository articleRepository;
    private final ProcedureRepository procedureRepository;
    private final ProcedureStepRepository procedureStepRepository;
    private final com.ecobank.rccportal.repository.CourseRepository courseRepository;
    private final AnthropicClient anthropicClient;
    private final WebSearchClient webSearchClient;
    private final DataProtectionService dataProtectionService;
    private final RafConversationMemoryService conversationMemoryService;
    private final DocumentTextExtractionService documentTextExtractionService;

    private final TranslationService translationService;
    private final com.ecobank.rccportal.raf.RafOrchestrator rafOrchestrator;

    public RalphSearchService(KnowledgeArticleRepository articleRepository, ProcedureRepository procedureRepository,
                              ProcedureStepRepository procedureStepRepository,
                              com.ecobank.rccportal.repository.CourseRepository courseRepository,
                              AnthropicClient anthropicClient,
                              WebSearchClient webSearchClient,
                              DataProtectionService dataProtectionService,
                              RafConversationMemoryService conversationMemoryService,
                              DocumentTextExtractionService documentTextExtractionService,
                              TranslationService translationService,
                              com.ecobank.rccportal.raf.RafOrchestrator rafOrchestrator) {
        this.articleRepository = articleRepository;
        this.procedureRepository = procedureRepository;
        this.procedureStepRepository = procedureStepRepository;
        this.courseRepository = courseRepository;
        this.anthropicClient = anthropicClient;
        this.webSearchClient = webSearchClient;
        this.dataProtectionService = dataProtectionService;
        this.conversationMemoryService = conversationMemoryService;
        this.documentTextExtractionService = documentTextExtractionService;
        this.translationService = translationService;
        this.rafOrchestrator = rafOrchestrator;
    }

    /** Compatibilité — sans identité ni langue. */
    public RalphSearchResponse ask(String question) {
        return ask(question, null);
    }

    /** Compatibilité — français par défaut. */
    public RalphSearchResponse ask(String question, String username) {
        return ask(question, username, null);
    }

    public RalphSearchResponse ask(String question, String username, String lang) {
        return ask(question, username, lang, null);
    }

    /**
     * Point d'entrée conversationnel de RAF — délégué à {@link RafOrchestrator} : agents locaux
     * spécialisés, AUCUNE IA externe ni appel réseau dans le chemin de réponse, chaque réponse
     * ancrée dans les données du portail et citée. {@code command} = action déterministe issue
     * d'un bouton du widget (mode guidé, choix proposé...).
     */
    public RalphSearchResponse ask(String question, String username, String lang, String command) {
        if ((question == null || question.isBlank()) && (command == null || command.isBlank())) {
            throw ApiException.badRequest("La question ne peut pas être vide.");
        }
        return rafOrchestrator.handle(question, username, lang, command);
    }

    /**
     * Traduit un texte libre (détection automatique de la langue source). Conservé pour
     * compatibilité — la logique de traduction vit maintenant dans {@link TranslationService}.
     */
    public String translate(String text, String targetLang) {
        return translationService.translate(text, "auto", targetLang).translatedText();
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
        return search(keyword, null);
    }

    /**
     * Recherche précisée par pays (filiale choisie par l'agent dans la fenêtre de recherche) :
     * les contenus d'un AUTRE pays sont écartés, ceux valables pour toutes les filiales (sans
     * pays) sont gardés, et ceux du pays choisi passent devant.
     */
    @Transactional(readOnly = true)
    public RalphSearchResponse search(String keyword, String countryCode) {
        if (keyword == null || keyword.isBlank()) {
            throw ApiException.badRequest("keyword is required.");
        }

        if (SearchText.queryTerms(keyword).isEmpty()) {
            return new RalphSearchResponse("Précise un peu plus ta recherche avec des mots plus spécifiques.", List.of());
        }

        String country = countryCode == null || countryCode.isBlank() ? null : countryCode.trim().toUpperCase(Locale.ROOT);
        InternalContext internal = buildInternalContext(keyword, country);
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
    private InternalContext buildInternalContext(String question, String country) {
        List<String> terms = SearchText.queryTerms(question);
        if (terms.isEmpty()) {
            return new InternalContext(List.of(), List.of(), List.of(), List.of());
        }
        int termCount = terms.size();

        List<Scored<KnowledgeArticle>> articleMatches = new ArrayList<>();
        for (KnowledgeArticle article : articleRepository.findAll()) {
            String articleCountry = article.getCountry() != null ? article.getCountry().getCountryCode() : null;
            if (country != null && articleCountry != null && !country.equalsIgnoreCase(articleCountry)) continue;
            SearchText.Match m = SearchText.score(question, terms,
                    SearchText.Field.of(article.getTitle(), 3.0),
                    SearchText.Field.of(article.getTags(), 2.0),
                    SearchText.Field.of(stripHtml(article.getContentHtml()), 1.0));
            if (m.isRelevant(termCount)) {
                articleMatches.add(new Scored<>(article, m.score() * (country != null && articleCountry != null ? 1.3 : 1.0)));
            }
        }
        articleMatches.sort((a, b) -> Double.compare(b.score, a.score));

        // Toutes les étapes en UNE requête (l'ancienne boucle faisait une requête SQL par procédure).
        List<Procedure> procedures = procedureRepository.findAll();
        Map<Integer, List<ProcedureStep>> stepsByProcedure = procedures.isEmpty() ? Map.of()
                : procedureStepRepository.findByProcedureIn(procedures).stream()
                .collect(java.util.stream.Collectors.groupingBy(st -> st.getProcedure().getProcedureId()));
        List<Scored<ProcedureStepMatch>> stepMatches = new ArrayList<>();
        for (Procedure procedure : procedures) {
            if (country != null && procedure.getCountryCode() != null && !country.equalsIgnoreCase(procedure.getCountryCode())) continue;
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

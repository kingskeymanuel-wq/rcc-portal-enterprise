package com.ecobank.rccportal.raf;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.raf.agent.RafAgent;
import com.ecobank.rccportal.raf.agent.SmallTalkAgent;
import com.ecobank.rccportal.raf.nlp.EntityExtractor;
import com.ecobank.rccportal.raf.nlp.FollowUpResolver;
import com.ecobank.rccportal.raf.nlp.IntentRouter;
import com.ecobank.rccportal.service.DataProtectionService;
import com.ecobank.rccportal.service.RafConversationMemoryService;
import com.ecobank.rccportal.util.LanguageDetector;
import com.ecobank.rccportal.util.SearchText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * RAF — assistant du RCC Portal, INDÉPENDANT de toute IA externe : un orchestrateur qui
 * consulte des agents spécialisés locaux (procédures, SLA, fiche appel, glossaire, agences,
 * planning, modèles de mail, Q/R vérifiées, documentation), chacun ancré dans une source de
 * données du portail et citant ce qu'il utilise.
 *
 * <p>Déroulé d'une question :</p>
 * <ol>
 *   <li>assainissement (DataProtectionService), langue de réponse, entités (pays, date...) ;</li>
 *   <li>relances en contexte : bouton / « suivant » / « 2 » / « détails » → commande
 *       déterministe ; « et pour le Sénégal ? » → question précédente + nouveau critère ;</li>
 *   <li>routage explicable des intentions, consultation des agents pertinents (3 max + la
 *       documentation en filet de sécurité) ;</li>
 *   <li>fusion : meilleure réponse, « à voir aussi », question de clarification si deux pistes
 *       sont aussi probables, confiance, trace « Pourquoi cette réponse ? » ;</li>
 *   <li>rien de fiable : RAF le dit (jamais d'invention) et la question est journalisée pour
 *       que la QA complète le contenu.</li>
 * </ol>
 */
@Service
public class RafOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RafOrchestrator.class);
    private static final double MIN_ROUTER_SCORE = 0.25;
    private static final int MAX_AGENTS = 3;

    private final List<RafAgent> agents;
    private final IntentRouter router;
    private final EntityExtractor extractor;
    private final FollowUpResolver followUps;
    private final RafConversationMemoryService memory;
    private final DataProtectionService dataProtection;
    private final RafGapLog gapLog;
    private final RafCatalog catalog;
    private Clock clock = Clock.systemDefaultZone();

    public RafOrchestrator(List<RafAgent> agents, IntentRouter router, EntityExtractor extractor, FollowUpResolver followUps,
                           RafConversationMemoryService memory, DataProtectionService dataProtection, RafGapLog gapLog,
                           RafCatalog catalog) {
        this.agents = agents;
        this.router = router;
        this.extractor = extractor;
        this.followUps = followUps;
        this.memory = memory;
        this.dataProtection = dataProtection;
        this.gapLog = gapLog;
        this.catalog = catalog;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Recherche au-delà du portail (web, pages officielles Ecobank) — optionnelle. */
    private RafWebResearch web;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWebResearch(RafWebResearch web) {
        this.web = web;
    }

    private boolean webAvailable() {
        return web != null && web.available();
    }

    /** Réponse tirée du web ; {@code null} si rien trouvé et {@code quiet} (repli silencieux). */
    private RalphSearchResponse webAnswer(RafRequest request, String query, boolean nothingInternal, boolean quiet) {
        List<com.ecobank.rccportal.dto.WebSearchResultItem> results = webAvailable() ? web.search(query) : List.of();
        if (results.isEmpty()) {
            if (quiet) return null;
            String md = "fr".equals(request.lang())
                    ? "🌐 Je n'ai rien trouvé sur le web pour « " + query + " » (ou l'accès à Internet est bloqué depuis le serveur — l'IT peut le vérifier via le diagnostic de recherche web)."
                    : "🌐 Nothing found on the web for “" + query + "” (or Internet access is blocked from the server).";
            remember(request, new RafDialogueState(null, request.question(), request.entities(), null, null, List.of()), md);
            return new RalphSearchResponse(md, List.of(), "NONE", List.of(), 10, List.of("Web"), "WEB", List.of(),
                    SmallTalkAgent.starters().subList(0, 3), null, false, List.of("recherche web sans résultat"));
        }
        String md = RafWebResearch.markdown(query, results, nothingInternal, request.lang());
        remember(request, new RafDialogueState(null, request.question(), request.entities(), null, null, List.of()), md);
        boolean official = results.stream().anyMatch(r -> r.url() != null && r.url().contains("ecobank.com"));
        return new RalphSearchResponse(md, List.of(), "WEB", results, official ? 60 : 45,
                List.of(official ? "Web (site officiel Ecobank)" : "Web"), "WEB", List.of(),
                List.of(), null, false,
                List.of("recherche externe (question assainie, sans données client) : " + results.size() + " résultat(s)"));
    }

    /** Liste des agents (id, libellé) — page d'aide / widget. */
    public List<Map<String, String>> capabilities() {
        return agents.stream().map(a -> Map.of("id", a.id(), "label", a.label())).toList();
    }

    /** Message d'accueil avec des exemples cliquables (sans rien mémoriser). */
    public RalphSearchResponse welcome(String lang) {
        String l = RafText.normalizeLang(lang);
        return new RalphSearchResponse(RafText.get(l, "hello"), List.of(), "LOCAL", List.of(), 90, List.of("Dialogue"),
                RafIntent.SMALL_TALK.name(), List.of(), SmallTalkAgent.starters(), null, false, List.of());
    }

    public RalphSearchResponse handle(String question, String username, String lang, String command) {
        String raw = question == null ? "" : question.trim();
        String sanitized = dataProtection.sanitize(raw);
        String replyLang = replyLanguage(lang, sanitized);
        LocalDateTime now = LocalDateTime.now(clock);
        RafDialogueState state = memory.state(username);
        // Les champs d'un modèle (nom du client, montant) sont lus dans la saisie brute mais ne
        // sont ni journalisés ni mémorisés : ils ne servent qu'à pré-remplir le brouillon.
        RafEntities entities = extractor.extract(raw, now.toLocalDate());
        // Les valeurs à insérer (nom, montant, référence...) ne sont pas des mots-clés de recherche.
        String searchable = sanitized;
        for (String value : entities.slotValues().values()) {
            if (value != null && !value.isBlank()) searchable = searchable.replace(value, " ");
        }
        searchable = searchable.replaceAll("(?i)\\b\\d[\\d\\s.,]*\\s*(fcfa|f cfa|xof|xaf|cfa|eur|usd)\\b", " ");
        RafRequest request = new RafRequest(sanitized, SearchText.normalize(sanitized), IntentRouter.contentTerms(SearchText.queryTerms(searchable)),
                replyLang, username, entities, state, command, now);

        String cmd = followUps.resolveCommand(request);
        if (cmd != null && cmd.startsWith("raf:web:")) {
            return webAnswer(request, cmd.substring("raf:web:".length()), false, false);
        }
        String explicitWeb = cmd == null ? RafWebResearch.explicitQuery(sanitized) : null;
        if (explicitWeb != null && webAvailable()) {
            return webAnswer(request, explicitWeb, false, false);
        }
        if (cmd != null) {
            if (cmd.startsWith("raf:ask:")) return handle(cmd.substring("raf:ask:".length()), username, lang, null);
            RalphSearchResponse commanded = handleCommand(request.withCommand(cmd), cmd);
            if (commanded != null) return commanded;
        }

        // Une phrase de conversation (« comment vas-tu », « ok », « merci ») n'est jamais la suite
        // elliptique de la question précédente.
        String expanded = com.ecobank.rccportal.raf.nlp.SmallTalk.detect(request) != null
                ? null : followUps.expandEllipsis(request);
        if (expanded != null) {
            RafEntities previous = state.lastEntities();
            if (entities.countryCode() != null && previous != null) {
                previous = new RafEntities(null, null, previous.date(), previous.stepNumber(), previous.level(), previous.slotValues());
            }
            RafEntities merged = entities.mergeOver(previous);
            request = request.withQuestion(expanded, SearchText.normalize(expanded), IntentRouter.contentTerms(SearchText.queryTerms(expanded)), merged);
        }
        return route(request);
    }

    // ══════════════════════════════════════════════════════════════════════

    /** Résultat de la consultation des agents pour une question. */
    private record Consultation(List<IntentScore> ranked, Map<RafIntent, IntentScore> byIntent, List<AgentAnswer> answers,
                                Map<String, Double> routerScores, List<RafAgentTrace> traces) {
    }

    /**
     * Interroge les agents pertinents. {@code wide} (demande de détails) : tous les agents de
     * connaissance sont consultés, pour croiser le maximum de sources sur le sujet.
     */
    private Consultation consult(RafRequest request, boolean wide) {
        List<IntentScore> ranked = router.route(request);
        Map<RafIntent, IntentScore> byIntent = new EnumMap<>(RafIntent.class);
        ranked.forEach(s -> byIntent.put(s.intent(), s));

        List<RafIntent> selected = new ArrayList<>();
        for (IntentScore s : ranked) {
            if (s.intent() == RafIntent.KNOWLEDGE) continue;
            if (s.score() >= MIN_ROUTER_SCORE && selected.size() < MAX_AGENTS) selected.add(s.intent());
        }
        selected.add(RafIntent.KNOWLEDGE);
        if (wide) {
            for (RafIntent i : List.of(RafIntent.PROCEDURE, RafIntent.SLA, RafIntent.GLOSSARY, RafIntent.VERIFIED_QA)) {
                if (!selected.contains(i)) selected.add(i);
            }
        }
        if (selected.contains(RafIntent.HELP) && !selected.contains(RafIntent.SMALL_TALK)) selected.add(RafIntent.SMALL_TALK);

        List<AgentAnswer> answers = new ArrayList<>();
        List<RafAgentTrace> traces = new ArrayList<>();
        Map<String, Double> routerScores = new HashMap<>();
        for (RafAgent agent : agents) {
            double score = agent.intents().stream().filter(selected::contains)
                    .mapToDouble(i -> byIntent.containsKey(i) ? byIntent.get(i).score() : 0).max().orElse(-1);
            if (score < 0) continue;
            if (agent.intents().contains(RafIntent.HELP) && byIntent.containsKey(RafIntent.HELP)) {
                score = Math.max(score, byIntent.get(RafIntent.HELP).score());
            }
            AgentAnswer a;
            try {
                a = agent.answer(request, score);
            } catch (RuntimeException e) {
                log.warn("RAF : l'agent {} a échoué : {}", agent.id(), e.getMessage());
                a = AgentAnswer.notFound(agent.id(), agent.intents().iterator().next(), "erreur interne");
            }
            routerScores.put(agent.id(), score);
            if (a.found()) answers.add(a);
            traces.add(new RafAgentTrace(agent.id(), agent.label(), pct(score), pct(a.matchScore()), false));
        }
        answers.sort(Comparator.comparingDouble((AgentAnswer a) -> combined(a, routerScores)).reversed());
        return new Consultation(ranked, byIntent, answers, routerScores, traces);
    }

    private RalphSearchResponse route(RafRequest request) {
        Consultation consultation = consult(request, false);
        List<IntentScore> ranked = consultation.ranked();
        Map<RafIntent, IntentScore> byIntent = consultation.byIntent();
        List<AgentAnswer> answers = consultation.answers();
        Map<String, Double> routerScores = consultation.routerScores();
        List<RafAgentTrace> traces = consultation.traces();

        if (answers.isEmpty()) return nothingFound(request, ranked, traces);

        // Fusion : combinaison routeur (l'intention est-elle la bonne ?) et correspondance (la
        // donnée trouvée colle-t-elle à la question ?) — déjà triée par consult().
        AgentAnswer primary = answers.get(0);
        double primaryScore = combined(primary, routerScores);

        boolean clarification = false;
        List<RafSuggestion> suggestions = new ArrayList<>(primary.suggestions());
        if (answers.size() > 1 && primaryScore < 0.7) {
            AgentAnswer second = answers.get(1);
            double secondScore = combined(second, routerScores);
            if (second.intent() != primary.intent() && secondScore >= 0.35 && primaryScore - secondScore < 0.1) {
                clarification = true;
                suggestions = List.of(clarifyChip(primary), clarifyChip(second));
            }
        }

        StringBuilder explanation = new StringBuilder();
        List<AgentAnswer> secondary = new ArrayList<>();
        if (clarification) {
            explanation.append(RafText.get(request.lang(), "clarify")).append("\n\n1. ").append(firstLine(primary.markdown()))
                    .append("\n2. ").append(firstLine(answers.get(1).markdown()));
        } else {
            for (AgentAnswer a : answers.subList(1, answers.size())) {
                if (secondary.size() >= 2) break;
                if (a.intent() == primary.intent() || a.intent() == RafIntent.SMALL_TALK) continue;
                if (primary.intent() == RafIntent.CALL_CARD && (a.intent() == RafIntent.PROCEDURE || a.intent() == RafIntent.SLA)) continue;
                if (combined(a, routerScores) >= Math.max(0.35, 0.6 * primaryScore)) secondary.add(a);
            }
            String synthesis = synthesize(request, primary, secondary);
            if (synthesis != null) {
                explanation.append(synthesis);
            } else {
                explanation.append(primary.markdown());
                // Les autres sources qui confirment ou complètent la réponse sont intégrées (2-3
                // lignes utiles chacune), pas seulement listées : RAF croise ses sources.
                for (AgentAnswer a : secondary) {
                    explanation.append("\n\n---\n🔎 **").append(RafText.get(request.lang(), "complement")).append(" — ")
                            .append(labelOf(a.agentId())).append("**\n").append(excerpt(a.markdown(), 3));
                }
            }
        }
        if (!"fr".equals(request.lang()) && primary.intent() != RafIntent.SMALL_TALK) {
            explanation.append("\n\n_").append(RafText.get(request.lang(), "dataFrench")).append("_");
        }

        Set<String> used = new HashSet<>();
        used.add(primary.agentId());
        secondary.forEach(a -> used.add(a.agentId()));
        if (clarification) used.add(answers.get(1).agentId());
        traces.replaceAll(t -> new RafAgentTrace(t.id(), t.label(), t.routerScore(), t.matchScore(), used.contains(t.id())));

        List<RalphResultItem> citations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AgentAnswer a : concat(primary, secondary)) {
            for (RalphResultItem c : a.citations()) {
                if (citations.size() < 12 && seen.add(c.sourceType() + ":" + c.id() + ":" + c.title())) citations.add(c);
            }
        }

        if (!clarification) suggestions = withNextSteps(request, primary, secondary, suggestions);
        if (!clarification && primary.intent() != RafIntent.SMALL_TALK && webAvailable()) {
            suggestions = new ArrayList<>(suggestions);
            suggestions.add(new RafSuggestion("fr".equals(request.lang()) ? "🌐 Chercher aussi sur le web" : "🌐 Search the web too",
                    request.question(), "raf:web:" + request.question()));
        }

        int confidence = primary.intent() == RafIntent.SMALL_TALK ? 90 : clarification ? 40
                : (int) Math.min(95, Math.round(100 * primaryScore) + (secondary.isEmpty() ? 0 : 5));

        List<String> reasoning = new ArrayList<>();
        IntentScore top = byIntent.get(primary.intent());
        if (top != null) reasoning.add("Intention « " + primary.intent().name() + " » : " + String.join(", ", top.evidence()));
        reasoning.addAll(primary.reasoning());
        secondary.forEach(a -> reasoning.addAll(a.reasoning()));

        RafDialogueState newState = new RafDialogueState(primary.intent(), request.question(), request.entities(),
                primary.details() != null ? primary.details() : request.state().lastDetails(), primary.guided(),
                clarification ? suggestions : (primary.action() != null && "CLARIFY".equals(primary.action().type())
                        ? primary.suggestions() : List.of()));
        remember(request, newState, explanation.toString());

        return new RalphSearchResponse(explanation.toString(), citations,
                primary.intent() == RafIntent.SMALL_TALK ? "LOCAL" : "INTERNAL", List.of(), confidence,
                concat(primary, secondary).stream().map(a -> labelOf(a.agentId())).distinct().toList(),
                primary.intent().name(), traces, suggestions, clarification ? null : primary.action(), clarification, reasoning);
    }

    private RalphSearchResponse handleCommand(RafRequest request, String cmd) {
        RafDialogueState state = request.state();
        AgentAnswer answer = null;
        if ("raf:details".equals(cmd)) {
            RalphSearchResponse deep = details(request);
            if (deep != null) return deep;
            if (state.lastDetails() == null) return null;
            answer = new AgentAnswer("details", state.lastIntent() != null ? state.lastIntent() : RafIntent.KNOWLEDGE, 1.0, true,
                    state.lastDetails(), null, List.of(), List.of(), null, state.guided(), List.of("détail de la réponse précédente"));
        } else if ("raf:stop".equals(cmd)) {
            answer = new AgentAnswer("procedure", RafIntent.PROCEDURE, 1.0, true, "👍 OK.", null, List.of(),
                    SmallTalkAgent.starters().subList(0, 3), null, null, List.of("fin du mode guidé"));
        } else if ("raf:help".equals(cmd)) {
            return handle("que sais tu faire", request.username(), request.lang(), null);
        } else {
            for (RafAgent agent : agents) {
                try {
                    answer = agent.onCommand(request, cmd);
                } catch (RuntimeException e) {
                    log.warn("RAF : commande {} en échec ({}) : {}", cmd, agent.id(), e.getMessage());
                }
                if (answer != null) break;
            }
        }
        if (answer == null) return null;

        RafDialogueState newState = new RafDialogueState(answer.intent(), state.lastQuestion(), state.lastEntities(),
                answer.details() != null ? answer.details() : state.lastDetails(), answer.guided(),
                answer.action() != null && "CLARIFY".equals(answer.action().type()) ? answer.suggestions() : List.of());
        remember(request, newState, answer.markdown());
        return new RalphSearchResponse(answer.markdown(), answer.citations(), "INTERNAL", List.of(), 95,
                List.of(labelOf(answer.agentId())), answer.intent().name(),
                List.of(new RafAgentTrace(answer.agentId(), labelOf(answer.agentId()), 100, 100, true)),
                answer.suggestions(), answer.action(), false, answer.reasoning());
    }

    /**
     * « Donne-moi plus de détails » : RAF reprend le SUJET précédent (et non la phrase de relance),
     * consulte toutes ses sources de connaissance et compose une réponse détaillée qui les croise,
     * chaque partie citée — au lieu de relancer une recherche sur les mots « plus de détails ».
     */
    private RalphSearchResponse details(RafRequest request) {
        RafDialogueState state = request.state();
        if (state.lastQuestion() == null) return null;
        String q = state.lastQuestion();
        RafRequest previous = request.withQuestion(q, SearchText.normalize(q), IntentRouter.contentTerms(SearchText.queryTerms(q)),
                state.lastEntities() != null ? state.lastEntities() : RafEntities.empty()).withCommand(null);
        Consultation c = consult(previous, true);
        List<AgentAnswer> useful = c.answers().stream()
                .filter(a -> a.intent() != RafIntent.SMALL_TALK && a.intent() != RafIntent.MY_SHIFT)
                .filter(a -> combined(a, c.routerScores()) >= 0.2 || a.matchScore() >= 0.5)
                .limit(4).toList();
        if (useful.isEmpty()) return null;

        StringBuilder md = new StringBuilder(RafText.get(request.lang(), "details.intro", q, useful.size()));
        List<RalphResultItem> citations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> reasoning = new ArrayList<>();
        List<RafSuggestion> chips = new ArrayList<>();
        for (AgentAnswer a : useful) {
            md.append("\n\n---\n**").append(labelOf(a.agentId())).append("**\n")
                    .append(a.details() != null ? a.details() : a.markdown());
            for (RalphResultItem ci : a.citations()) {
                if (citations.size() < 12 && seen.add(ci.sourceType() + ":" + ci.id() + ":" + ci.title())) citations.add(ci);
            }
            reasoning.addAll(a.reasoning());
            for (RafSuggestion sg : a.suggestions()) {
                if (chips.size() < 4 && !"raf:details".equals(sg.command())) chips.add(sg);
            }
        }
        Set<String> usedIds = new HashSet<>();
        useful.forEach(a -> usedIds.add(a.agentId()));
        List<RafAgentTrace> traces = c.traces().stream()
                .map(t -> new RafAgentTrace(t.id(), t.label(), t.routerScore(), t.matchScore(), usedIds.contains(t.id()))).toList();
        reasoning.add(0, "détail du sujet « " + q + " » : " + useful.size() + " source(s) croisée(s)");

        RafDialogueState newState = new RafDialogueState(useful.get(0).intent(), q, state.lastEntities(), md.toString(),
                state.guided(), List.of());
        remember(request, newState, md.toString());
        return new RalphSearchResponse(md.toString(), citations, "INTERNAL", List.of(), 90,
                useful.stream().map(a -> labelOf(a.agentId())).toList(), useful.get(0).intent().name(), traces, chips, null,
                false, reasoning);
    }

    /**
     * SYNTHÈSE — quand plusieurs sources parlent du même sujet (définition du glossaire, réponse
     * validée par la QA, article, procédure, SLA), RAF ne les empile pas : il compose UNE réponse
     * à partir de leurs données structurées — ce que c'est, ce qu'il faut en retenir, ce qu'il faut
     * faire pour le client, le délai officiel — sans doublon, puis indique les sources croisées.
     * Ne s'applique qu'aux questions de connaissance (définition, documentation, Q/R) ; null sinon.
     */
    String synthesize(RafRequest request, AgentAnswer primary, List<AgentAnswer> secondary) {
        if (!EnumSet.of(RafIntent.GLOSSARY, RafIntent.VERIFIED_QA, RafIntent.KNOWLEDGE).contains(primary.intent())) return null;
        if (secondary.isEmpty()) return null;
        RafDocs.Snapshot data = catalog.snapshot();
        String lang = request.lang();
        RafDocs.TermDoc term = null;
        RafDocs.VerifiedQaDoc qa = null;
        RafDocs.ArticleDoc article = null;
        RafDocs.ProcedureDoc procedure = null;
        RafDocs.SlaDoc sla = null;
        for (AgentAnswer a : concat(primary, secondary)) {
            for (RalphResultItem c : a.citations()) {
                if (c.id() == null) continue;
                int id = c.id();
                switch (c.sourceType()) {
                    case "TERM" -> { if (term == null) term = data.terms().stream().filter(t -> t.id() == id).findFirst().orElse(null); }
                    case "QUIZ" -> { if (qa == null) qa = data.verifiedQa().stream().filter(q -> q.id() == id).findFirst().orElse(null); }
                    case "ARTICLE" -> { if (article == null) article = data.articles().stream().filter(x -> x.id() == id).findFirst().orElse(null); }
                    case "PROCEDURE" -> { if (procedure == null) procedure = data.procedures().stream().filter(p -> p.id() == id).findFirst().orElse(null); }
                    case "SLA" -> { if (sla == null) sla = data.slaRules().stream().filter(r -> r.id() == id).findFirst().orElse(null); }
                    default -> { }
                }
            }
        }
        int facts = (term != null ? 1 : 0) + (qa != null ? 1 : 0) + (article != null ? 1 : 0) + (procedure != null ? 1 : 0) + (sla != null ? 1 : 0);
        if (facts < 2) return null;

        StringBuilder md = new StringBuilder();
        List<String> said = new ArrayList<>();   // phrases déjà dites (normalisées) — pas de répétition
        List<String> sources = new ArrayList<>();
        if (term != null) {
            md.append("**").append(term.term()).append("** — ").append(term.definition());
            said.add(SearchText.normalize(term.definition()));
            sources.add(labelOf("glossary"));
        }
        if (qa != null) {
            String answer = SearchText.normalize(qa.answer());
            boolean redundant = said.stream().anyMatch(x -> x.contains(answer) || answer.contains(x));
            if (md.length() == 0) md.append("**").append(qa.answer()).append("**");
            else if (!redundant) md.append("\n").append(RafText.get(lang, "synth.qa", qa.answer()));
            String explanation = qa.explanation() == null ? "" : SearchText.normalize(qa.explanation());
            if (!explanation.isBlank() && said.stream().noneMatch(x -> x.contains(explanation))) {
                md.append("\n").append(qa.explanation());
                said.add(explanation);
            }
            said.add(answer);
            sources.add(labelOf("verifiedqa"));
        }
        if (article != null) {
            String extract = SearchText.bestSentences(article.plainText(), request.terms(), 2, 320);
            if (!extract.isBlank() && said.stream().noneMatch(x -> x.contains(SearchText.normalize(extract)))) {
                if (md.length() > 0) md.append("\n");
                md.append(RafText.get(lang, "synth.from", article.title())).append(" ").append(extract);
            }
            sources.add("« " + article.title() + " »");
        }
        if (procedure != null && !procedure.steps().isEmpty()) {
            md.append("\n\n👉 ").append(RafText.get(lang, "synth.proc", procedure.title(), procedure.steps().size(),
                    trim(procedure.steps().get(0).content(), 110)));
            sources.add("« " + procedure.title() + " »");
        }
        if (sla != null) {
            md.append("\n⏱ ").append(RafText.get(lang, "synth.sla", sla.slaLabel(), sla.motif()));
            sources.add(labelOf("sla"));
        }
        md.append("\n\n_").append(RafText.get(lang, "synth.sources", String.join(" · ", sources))).append("_");
        return md.toString();
    }

    /**
     * Prochaines étapes logiques tirées des données : pour une définition ou une réponse
     * documentaire, la procédure et le délai qui portent sur le même sujet (ex. « RIB » →
     * « Demande de RIB », son SLA) — RAF anticipe la question suivante de l'agent.
     */
    private List<RafSuggestion> withNextSteps(RafRequest request, AgentAnswer primary, List<AgentAnswer> secondary,
                                              List<RafSuggestion> current) {
        List<RafSuggestion> out = new ArrayList<>(current);
        if (out.size() >= 4 || primary.intent() == RafIntent.SMALL_TALK || primary.intent() == RafIntent.MY_SHIFT
                || primary.guided() != null || request.terms().isEmpty()) return out;
        Set<String> already = new HashSet<>();
        out.forEach(sg -> already.add(String.valueOf(sg.command())));
        Set<RafIntent> covered = EnumSet.of(primary.intent());
        secondary.forEach(a -> covered.add(a.intent()));
        // Chaque complément intégré à la réponse a son bouton (ouvrir la procédure, le SLA…).
        for (AgentAnswer a : secondary) {
            if (a.citations().isEmpty()) continue;
            RalphResultItem ci = a.citations().get(0);
            String cmd = commandFor(ci);
            if (cmd != null && already.add(cmd)) {
                if ("PROCEDURE".equals(ci.sourceType())) {
                    out.add(new RafSuggestion("▶ " + RafText.get(request.lang(), "guided.start") + " : " + trim(ci.title(), 38),
                            null, cmd + ":step:1"));
                } else {
                    out.add(new RafSuggestion(("SLA".equals(ci.sourceType()) ? "⏱ " : "✉ ") + trim(ci.title(), 45), null, cmd));
                }
            }
        }
        RafDocs.Snapshot data = catalog.snapshot();
        int n = request.terms().size();
        if (!covered.contains(RafIntent.PROCEDURE) && !covered.contains(RafIntent.CALL_CARD)) {
            data.procedures().stream()
                    .filter(p -> SearchText.score(request.question(), request.terms(), SearchText.Field.of(p.title(), 1)).isRelevant(n))
                    .limit(2).forEach(p -> {
                        String cmd = "raf:proc:" + p.id();
                        if (already.add(cmd)) out.add(new RafSuggestion("📋 " + trim(p.title(), 45), null, cmd));
                    });
        }
        if (!covered.contains(RafIntent.SLA) && !covered.contains(RafIntent.CALL_CARD)) {
            data.slaRules().stream()
                    .filter(r -> SearchText.score(request.question(), request.terms(), SearchText.Field.of(r.motif(), 1)).isRelevant(n))
                    .limit(1).forEach(r -> {
                        String cmd = "raf:sla:" + r.id();
                        if (already.add(cmd)) out.add(new RafSuggestion("⏱ " + trim(r.motif(), 45), null, cmd));
                    });
        }
        if (primary.details() != null || !secondary.isEmpty()) {
            out.add(new RafSuggestion("🔍 " + RafText.get(request.lang(), "details"), null, "raf:details"));
        }
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    private static String trim(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max).trim() + "…";
    }

    /** Premières lignes utiles d'une réponse (sans son titre), pour l'intégrer en complément. */
    private static String excerpt(String markdown, int lines) {
        if (markdown == null) return "";
        List<String> kept = new ArrayList<>();
        for (String line : markdown.split("\n")) {
            if (line.isBlank()) continue;
            kept.add(line.trim());
            if (kept.size() >= lines + 1) break;
        }
        if (kept.size() > 1 && kept.get(0).startsWith("**")) {
            kept.set(0, kept.get(0));
        }
        return String.join("\n", kept);
    }

    private RalphSearchResponse nothingFound(RafRequest request, List<IntentScore> ranked, List<RafAgentTrace> traces) {
        if (request.terms().isEmpty() && request.normalized().split(" ").length <= 6) {
            // Aucun mot métier : c'est une phrase de conversation que RAF ne connaît pas encore.
            // On relance la discussion au lieu d'un « rien trouvé » froid (et ce n'est pas une lacune QA).
            String md = RafText.get(request.lang(), "chat.fallback");
            remember(request, new RafDialogueState(RafIntent.SMALL_TALK, request.question(), request.entities(), null, null, List.of()), md);
            return new RalphSearchResponse(md, List.of(), "LOCAL", List.of(), 60, List.of("Dialogue"), RafIntent.SMALL_TALK.name(), traces,
                    SmallTalkAgent.starters().subList(0, 4), null, false, List.of("phrase de conversation sans mot métier"));
        }
        String best = ranked.isEmpty() ? null : ranked.get(0).intent().name();
        gapLog.record(request.question(), best);
        // Rien dans le portail : RAF va chercher ailleurs au lieu de s'arrêter là.
        if (webAvailable()) {
            RalphSearchResponse fromWeb = webAnswer(request, request.question(), true, true);
            if (fromWeb != null) return fromWeb;
        }
        String md = RafText.get(request.lang(), "nothing", request.question()) + "\n\n" + RafText.get(request.lang(), "nothing.tip");
        remember(request, new RafDialogueState(null, request.question(), request.entities(), null, null, List.of()), md);
        return new RalphSearchResponse(md, List.of(), "NONE", List.of(), 10, List.of(), null, traces,
                SmallTalkAgent.starters().subList(0, 4), null, false,
                List.of("aucun agent n'a trouvé de donnée fiable — question transmise à la QA (lacunes de contenu)"));
    }

    private void remember(RafRequest request, RafDialogueState state, String answer) {
        if (request.username() == null) return;
        memory.saveState(request.username(), state);
        memory.record(request.username(), request.question(), firstLine(answer));
    }

    // ══════════════════════════════════════════════════════════════════════

    private static double combined(AgentAnswer a, Map<String, Double> routerScores) {
        return 0.45 * routerScores.getOrDefault(a.agentId(), 0.0) + 0.55 * a.matchScore();
    }

    private RafSuggestion clarifyChip(AgentAnswer a) {
        String label = labelOf(a.agentId()) + " : " + firstLine(a.markdown()).replace("*", "");
        String cmd = a.citations().isEmpty() ? null : commandFor(a.citations().get(0));
        return new RafSuggestion(label.length() > 70 ? label.substring(0, 70) + "…" : label, null, cmd);
    }

    private static String commandFor(RalphResultItem c) {
        if (c.id() == null) return null;
        return switch (c.sourceType()) {
            case "PROCEDURE" -> "raf:proc:" + c.id();
            case "SLA" -> "raf:sla:" + c.id();
            case "MAIL_TEMPLATE" -> "raf:tpl:" + c.id();
            default -> null;
        };
    }

    private String labelOf(String agentId) {
        return agents.stream().filter(a -> a.id().equals(agentId)).map(RafAgent::label).findFirst().orElse(agentId);
    }

    private static List<AgentAnswer> concat(AgentAnswer primary, List<AgentAnswer> others) {
        List<AgentAnswer> out = new ArrayList<>();
        out.add(primary);
        out.addAll(others);
        return out;
    }

    private static String firstLine(String markdown) {
        if (markdown == null) return "";
        for (String line : markdown.split("\n")) {
            if (!line.isBlank()) return line.trim();
        }
        return "";
    }

    private static int pct(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 100);
    }

    /** Langue choisie dans le widget, sinon langue détectée de la question (fr/en/pt/es), sinon français. */
    static String replyLanguage(String requested, String question) {
        if (requested != null && !requested.isBlank()) return RafText.normalizeLang(requested);
        String detected = LanguageDetector.detect(question);
        return detected != null && Set.of("fr", "en", "pt", "es").contains(detected) ? detected : "fr";
    }
}

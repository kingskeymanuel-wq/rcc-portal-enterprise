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
    private Clock clock = Clock.systemDefaultZone();

    public RafOrchestrator(List<RafAgent> agents, IntentRouter router, EntityExtractor extractor, FollowUpResolver followUps,
                           RafConversationMemoryService memory, DataProtectionService dataProtection, RafGapLog gapLog) {
        this.agents = agents;
        this.router = router;
        this.extractor = extractor;
        this.followUps = followUps;
        this.memory = memory;
        this.dataProtection = dataProtection;
        this.gapLog = gapLog;
    }

    void setClock(Clock clock) {
        this.clock = clock;
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
        RafRequest request = new RafRequest(sanitized, SearchText.normalize(sanitized), SearchText.queryTerms(searchable),
                replyLang, username, entities, state, command, now);

        String cmd = followUps.resolveCommand(request);
        if (cmd != null) {
            if (cmd.startsWith("raf:ask:")) return handle(cmd.substring("raf:ask:".length()), username, lang, null);
            RalphSearchResponse commanded = handleCommand(request.withCommand(cmd), cmd);
            if (commanded != null) return commanded;
        }

        String expanded = followUps.expandEllipsis(request);
        if (expanded != null) {
            RafEntities previous = state.lastEntities();
            if (entities.countryCode() != null && previous != null) {
                previous = new RafEntities(null, null, previous.date(), previous.stepNumber(), previous.level(), previous.slotValues());
            }
            RafEntities merged = entities.mergeOver(previous);
            request = request.withQuestion(expanded, SearchText.normalize(expanded), SearchText.queryTerms(expanded), merged);
        }
        return route(request);
    }

    // ══════════════════════════════════════════════════════════════════════

    private RalphSearchResponse route(RafRequest request) {
        List<IntentScore> ranked = router.route(request);
        Map<RafIntent, IntentScore> byIntent = new EnumMap<>(RafIntent.class);
        ranked.forEach(s -> byIntent.put(s.intent(), s));

        List<RafIntent> selected = new ArrayList<>();
        for (IntentScore s : ranked) {
            if (s.intent() == RafIntent.KNOWLEDGE) continue;
            if (s.score() >= MIN_ROUTER_SCORE && selected.size() < MAX_AGENTS) selected.add(s.intent());
        }
        selected.add(RafIntent.KNOWLEDGE);
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

        if (answers.isEmpty()) return nothingFound(request, ranked, traces);

        // Fusion : combinaison routeur (l'intention est-elle la bonne ?) et correspondance (la
        // donnée trouvée colle-t-elle à la question ?).
        answers.sort(Comparator.comparingDouble((AgentAnswer a) -> combined(a, routerScores)).reversed());
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
            explanation.append(primary.markdown());
            for (AgentAnswer a : answers.subList(1, answers.size())) {
                if (secondary.size() >= 2) break;
                if (a.intent() == primary.intent() || a.intent() == RafIntent.SMALL_TALK) continue;
                if (primary.intent() == RafIntent.CALL_CARD && (a.intent() == RafIntent.PROCEDURE || a.intent() == RafIntent.SLA)) continue;
                if (combined(a, routerScores) >= Math.max(0.35, 0.6 * primaryScore)) secondary.add(a);
            }
            if (!secondary.isEmpty()) {
                explanation.append("\n\n---\n🔎 **").append(RafText.get(request.lang(), "seeAlso")).append("**");
                for (AgentAnswer a : secondary) {
                    explanation.append("\n• ").append(labelOf(a.agentId())).append(" — ").append(firstLine(a.markdown()));
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

    private RalphSearchResponse nothingFound(RafRequest request, List<IntentScore> ranked, List<RafAgentTrace> traces) {
        String best = ranked.isEmpty() ? null : ranked.get(0).intent().name();
        gapLog.record(request.question(), best);
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

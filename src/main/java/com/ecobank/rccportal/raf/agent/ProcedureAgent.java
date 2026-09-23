package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.RafAction;
import com.ecobank.rccportal.dto.RafSuggestion;
import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.raf.RafCatalog;
import com.ecobank.rccportal.raf.RafDocs.ProcedureDoc;
import com.ecobank.rccportal.raf.RafDocs.StepDoc;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.raf.RafText;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Agent Procédures — retrouve la bonne fiche (titre + contenu des étapes, pays) et propose le
 * MODE GUIDÉ : l'agent déroule la procédure étape par étape pendant l'appel (« suivant »,
 * « précédent », « étape 4 », à la voix ou par bouton). Plusieurs fiches aussi pertinentes :
 * RAF demande laquelle au lieu de choisir au hasard.
 */
@Component
public class ProcedureAgent implements RafAgent {

    private final RafCatalog catalog;

    public ProcedureAgent(RafCatalog catalog) {
        this.catalog = catalog;
    }

    public String id() { return "procedure"; }

    public String label() { return "Procédures internes"; }

    public Set<RafIntent> intents() { return Set.of(RafIntent.PROCEDURE); }

    public List<Scoring.Scored<ProcedureDoc>> rank(RafRequest request) {
        String country = request.entities().countryCode();
        List<Scoring.Scored<ProcedureDoc>> ranked = new ArrayList<>(Scoring.rank(request, catalog.snapshot().procedures(), p -> {
            StringBuilder steps = new StringBuilder();
            p.steps().forEach(s -> steps.append(s.content()).append(' '));
            return new SearchText.Field[]{SearchText.Field.of(p.title(), 3), SearchText.Field.of(steps.toString(), 1)};
        }));
        if (country != null) {
            // Fiche du pays demandé en tête ; fiche d'un autre pays nettement pénalisée.
            ranked.replaceAll(s -> new Scoring.Scored<>(s.doc(), s.doc().countryCode() == null ? s.score()
                    : country.equalsIgnoreCase(s.doc().countryCode()) ? Math.min(1, s.score() * 1.2) : s.score() * 0.6));
            ranked.sort(Comparator.comparingDouble((Scoring.Scored<ProcedureDoc> s) -> s.score()).reversed());
        }
        return ranked;
    }

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        List<Scoring.Scored<ProcedureDoc>> ranked = rank(request);
        if (ranked.isEmpty()) return AgentAnswer.notFound(id(), RafIntent.PROCEDURE, "aucune procédure correspondante");
        Scoring.Scored<ProcedureDoc> top = ranked.get(0);
        List<Scoring.Scored<ProcedureDoc>> close = ranked.stream().filter(s -> s.score() >= top.score() * 0.85).limit(4).toList();
        if (close.size() > 1) {
            StringBuilder md = new StringBuilder(RafText.get(request.lang(), "clarify")).append("\n");
            List<RafSuggestion> choices = new ArrayList<>();
            int i = 1;
            for (Scoring.Scored<ProcedureDoc> s : close) {
                md.append("\n").append(i++).append(". 📋 ").append(s.doc().title());
                if (s.doc().countryCode() != null) md.append(" (").append(s.doc().countryCode()).append(")");
                choices.add(new RafSuggestion(Scoring.truncate(s.doc().title(), 60), null, "raf:proc:" + s.doc().id()));
            }
            return new AgentAnswer(id(), RafIntent.PROCEDURE, top.score() * 0.9, true, md.toString(), null,
                    close.stream().map(s -> citation(s.doc())).toList(), choices,
                    new RafAction("CLARIFY", Map.of("count", choices.size())), null,
                    List.of(close.size() + " procédures aussi pertinentes — question de clarification"));
        }
        return summary(request, top.doc(), top.score());
    }

    @Override
    public AgentAnswer onCommand(RafRequest request, String command) {
        if (!command.startsWith("raf:proc:")) return null;
        String[] parts = command.split(":");
        try {
            int id = Integer.parseInt(parts[2]);
            ProcedureDoc p = catalog.snapshot().procedures().stream().filter(x -> x.id() == id).findFirst().orElse(null);
            if (p == null) return null;
            if (parts.length >= 5 && "step".equals(parts[3])) return step(request, p, Integer.parseInt(parts[4]));
            return summary(request, p, 1.0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    AgentAnswer summary(RafRequest request, ProcedureDoc p, double score) {
        StringBuilder md = new StringBuilder("**📋 ").append(p.title()).append("**");
        List<String> meta = new ArrayList<>();
        if (p.countryCode() != null) meta.add(p.countryCode());
        if (p.level() != null && !p.level().isBlank()) meta.add(p.level());
        if (p.responsibleTeam() != null && !p.responsibleTeam().isBlank()) meta.add("équipe : " + p.responsibleTeam());
        if (!meta.isEmpty()) md.append(" — ").append(String.join(" · ", meta));
        md.append("\n");
        List<StepDoc> steps = p.steps();
        steps.stream().limit(3).forEach(s -> md.append("\n").append(s.number()).append(". ").append(Scoring.truncate(s.content(), 220)));
        if (steps.size() > 3) md.append("\n… (").append(steps.size() - 3).append(" étape(s) de plus)");
        if (steps.isEmpty()) md.append("\nÉtapes pas encore rédigées par la QA.");

        StringBuilder details = new StringBuilder("**📋 ").append(p.title()).append("**\n");
        steps.forEach(s -> details.append("\n").append(s.number()).append(". ").append(s.content()));
        if (p.slaDelay() != null && !p.slaDelay().isBlank()) details.append("\n\nDélai indiqué sur la fiche : ").append(p.slaDelay());

        List<RafSuggestion> chips = new ArrayList<>();
        if (!steps.isEmpty()) chips.add(new RafSuggestion("▶ " + RafText.get(request.lang(), "guided.start"), null, "raf:proc:" + p.id() + ":step:1"));
        if (steps.size() > 3) chips.add(new RafSuggestion(RafText.get(request.lang(), "details"), null, "raf:details"));
        chips.add(new RafSuggestion("📞 " + RafText.get(request.lang(), "callcard.title"), null, "raf:callcard:" + p.id()));
        return new AgentAnswer(id(), RafIntent.PROCEDURE, score, true, md.toString(), details.toString(),
                List.of(citation(p)), chips, null, null, List.of("procédure « " + p.title() + " »"));
    }

    AgentAnswer step(RafRequest request, ProcedureDoc p, int stepNumber) {
        List<StepDoc> steps = p.steps();
        if (steps.isEmpty()) return summary(request, p, 1.0);
        String lang = request.lang();
        if (stepNumber > steps.size()) {
            List<RafSuggestion> chips = List.of(
                    new RafSuggestion("📞 " + RafText.get(lang, "callcard.title"), null, "raf:callcard:" + p.id()),
                    new RafSuggestion(RafText.get(lang, "prev"), null, "raf:proc:" + p.id() + ":step:" + steps.size()));
            return new AgentAnswer(id(), RafIntent.PROCEDURE, 1.0, true, "✅ " + RafText.get(lang, "guided.done"), null,
                    List.of(citation(p)), chips, null, null, List.of("fin du mode guidé"));
        }
        int n = Math.max(1, stepNumber);
        StepDoc s = steps.get(n - 1);
        String md = "**" + p.title() + "** — " + RafText.get(lang, "guided.step", n, steps.size()) + "\n\n" + s.content();
        List<RafSuggestion> chips = new ArrayList<>();
        if (n > 1) chips.add(new RafSuggestion("◀ " + RafText.get(lang, "prev"), null, "raf:proc:" + p.id() + ":step:" + (n - 1)));
        chips.add(new RafSuggestion(RafText.get(lang, "next") + " ▶", null, "raf:proc:" + p.id() + ":step:" + (n + 1)));
        chips.add(new RafSuggestion(RafText.get(lang, "stop"), null, "raf:stop"));
        RafAction action = new RafAction("GUIDED_STEP", Map.of("procedureId", p.id(), "title", p.title(),
                "step", n, "total", steps.size(), "content", s.content()));
        return new AgentAnswer(id(), RafIntent.PROCEDURE, 1.0, true, md, null, List.of(citation(p)), chips, action,
                new GuidedSession(p.id(), n), List.of("mode guidé, étape " + n));
    }

    private static RalphResultItem citation(ProcedureDoc p) {
        String first = p.steps().isEmpty() ? "" : p.steps().get(0).content();
        return new RalphResultItem("PROCEDURE", p.id(), p.title(), Scoring.truncate(first, 160));
    }
}

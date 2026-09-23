package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.RafAction;
import com.ecobank.rccportal.dto.RafSuggestion;
import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.raf.RafCatalog;
import com.ecobank.rccportal.raf.RafDocs.SlaDoc;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.raf.RafText;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Agent SLA — délais UNIQUEMENT issus du référentiel officiel (table SlaRules, éditable dans
 * l'Administration). Donne le libellé officiel tel quel et calcule l'échéance à annoncer au
 * client à partir de maintenant. Motif absent du référentiel : le dit clairement, sans jamais
 * estimer un délai.
 */
@Component
public class SlaAgent implements RafAgent {

    private final RafCatalog catalog;

    public SlaAgent(RafCatalog catalog) {
        this.catalog = catalog;
    }

    public String id() { return "sla"; }

    public String label() { return "Référentiel SLA"; }

    public Set<RafIntent> intents() { return Set.of(RafIntent.SLA); }

    /** Meilleure règle SLA pour la question (utilisée aussi par la fiche appel). */
    public Optional<Scoring.Scored<SlaDoc>> best(RafRequest request) {
        List<SlaDoc> rules = catalog.snapshot().slaRules();
        String level = request.entities().level();
        List<Scoring.Scored<SlaDoc>> ranked = Scoring.rank(request, rules, r -> new SearchText.Field[]{
                SearchText.Field.of(r.motif(), 3), SearchText.Field.of(r.category(), 2), SearchText.Field.of(r.notes(), 1)});
        if (level != null) {
            List<Scoring.Scored<SlaDoc>> sameLevel = ranked.stream().filter(s -> level.equalsIgnoreCase(s.doc().level())).toList();
            if (!sameLevel.isEmpty()) ranked = sameLevel;
        }
        return ranked.stream().findFirst();
    }

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        Optional<Scoring.Scored<SlaDoc>> best = best(request);
        if (best.isEmpty()) {
            if (routerScore >= 0.5 && !catalog.snapshot().slaRules().isEmpty()) {
                // Question clairement sur un délai, motif inconnu : réponse sûre plutôt qu'une estimation.
                Set<String> categories = new TreeSet<>();
                catalog.snapshot().slaRules().forEach(r -> { if (r.category() != null) categories.add(r.category()); });
                List<RafSuggestion> chips = categories.stream().limit(5)
                        .map(c -> new RafSuggestion("SLA " + c, "délais SLA " + c, null)).toList();
                return new AgentAnswer(id(), RafIntent.SLA, 0.6, true, RafText.get(request.lang(), "sla.unknown"), null,
                        List.of(), chips, null, null, List.of("aucun motif du référentiel ne correspond"));
            }
            return AgentAnswer.notFound(id(), RafIntent.SLA, "aucune règle SLA correspondante");
        }
        return render(request, best.get().doc(), best.get().score());
    }

    @Override
    public AgentAnswer onCommand(RafRequest request, String command) {
        if (!command.startsWith("raf:sla:")) return null;
        try {
            int id = Integer.parseInt(command.substring("raf:sla:".length()));
            return catalog.snapshot().slaRules().stream().filter(r -> r.id() == id).findFirst()
                    .map(r -> render(request, r, 1.0)).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AgentAnswer render(RafRequest request, SlaDoc r, double score) {
        StringBuilder md = new StringBuilder();
        md.append("**⏱ ").append(r.motif()).append("**");
        if (r.level() != null && !r.level().isBlank()) md.append(" (").append(r.level()).append(")");
        md.append("\n\nSLA officiel : **").append(r.slaLabel()).append("**");
        if (r.destinationService() != null && !r.destinationService().isBlank()) {
            md.append("\nService de traitement : ").append(r.destinationService());
        }
        if (r.autoEscalation()) md.append("\nUn traitement automatique (reverse) s'applique d'abord, avant l'ouverture d'un dossier manuel.");
        if (r.notes() != null && !r.notes().isBlank()) md.append("\n").append(r.notes());

        RafAction action = null;
        Optional<java.time.LocalDateTime> due = SlaCalculator.dueDate(request.now(), r);
        if (due.isPresent()) {
            String when = RafText.formatDateTime(request.lang(), due.get());
            md.append("\n\n📅 ").append(RafText.get(request.lang(), "sla.due", when)).append(" ")
                    .append(RafText.get(request.lang(), "sla.holidays"));
            action = new RafAction("SLA_DUE", Map.of("motif", r.motif(), "slaLabel", r.slaLabel(),
                    "dueAt", due.get().toString(), "dueLabel", when));
        }
        return new AgentAnswer(id(), RafIntent.SLA, score, true, md.toString(), null,
                List.of(new RalphResultItem("SLA", r.id(), r.motif(), r.slaLabel())),
                List.of(), action, null, List.of("règle SLA « " + r.motif() + " » (référentiel officiel)"));
    }
}

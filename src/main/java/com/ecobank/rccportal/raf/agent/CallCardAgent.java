package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.RafAction;
import com.ecobank.rccportal.dto.RafSuggestion;
import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.raf.RafCatalog;
import com.ecobank.rccportal.raf.RafDocs.MailTemplateDoc;
import com.ecobank.rccportal.raf.RafDocs.ProcedureDoc;
import com.ecobank.rccportal.raf.RafDocs.SlaDoc;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.raf.RafText;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * FICHE APPEL — la spécialité de RAF : en une réponse, pendant l'appel, tout ce dont le
 * conseiller a besoin, assemblé par les autres agents et chaque ligne citée :
 * <ul>
 *   <li><b>Ce que je fais</b> : étapes clés de la procédure (Agent Procédures) ;</li>
 *   <li><b>Ce que je dis au client</b> : phrase prête à dire avec le délai OFFICIEL et la date
 *       d'échéance calculée (Agent SLA) — jamais un délai inventé ;</li>
 *   <li><b>Où transmettre</b> : service de traitement / équipe responsable ;</li>
 *   <li><b>Modèle à envoyer</b> : le mail officiel correspondant (Agent Modèles) ;</li>
 *   <li><b>Termes utiles</b> : sigles du glossaire présents dans la procédure.</li>
 * </ul>
 * Une rubrique sans donnée fiable est simplement omise.
 */
@Component
public class CallCardAgent implements RafAgent {

    private final RafCatalog catalog;
    private final ProcedureAgent procedureAgent;
    private final SlaAgent slaAgent;
    private final TemplateAgent templateAgent;

    public CallCardAgent(RafCatalog catalog, ProcedureAgent procedureAgent, SlaAgent slaAgent, TemplateAgent templateAgent) {
        this.catalog = catalog;
        this.procedureAgent = procedureAgent;
        this.slaAgent = slaAgent;
        this.templateAgent = templateAgent;
    }

    public String id() { return "callcard"; }

    public String label() { return "Fiche appel"; }

    public Set<RafIntent> intents() { return Set.of(RafIntent.CALL_CARD); }

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        List<Scoring.Scored<ProcedureDoc>> procs = procedureAgent.rank(request);
        Optional<Scoring.Scored<SlaDoc>> sla = slaAgent.best(request);
        if (procs.isEmpty() && sla.isEmpty()) return AgentAnswer.notFound(id(), RafIntent.CALL_CARD, "ni procédure ni SLA correspondants");
        ProcedureDoc proc = procs.isEmpty() ? null : procs.get(0).doc();
        double score = Math.max(procs.isEmpty() ? 0 : procs.get(0).score(), sla.map(Scoring.Scored::score).orElse(0.0));
        return build(request, proc, sla.map(Scoring.Scored::doc).orElse(null), templateAgent.best(request).map(Scoring.Scored::doc).orElse(null), score);
    }

    @Override
    public AgentAnswer onCommand(RafRequest request, String command) {
        if (!command.startsWith("raf:callcard:")) return null;
        try {
            int id = Integer.parseInt(command.substring("raf:callcard:".length()));
            ProcedureDoc proc = catalog.snapshot().procedures().stream().filter(p -> p.id() == id).findFirst().orElse(null);
            if (proc == null) return null;
            // Recherche du SLA et du modèle à partir du TITRE de la procédure.
            RafRequest byTitle = request.withQuestion(proc.title(), SearchText.normalize(proc.title()),
                    SearchText.queryTerms(proc.title()), request.entities());
            return build(request, proc, slaAgent.best(byTitle).map(Scoring.Scored::doc).orElse(null),
                    templateAgent.best(byTitle).map(Scoring.Scored::doc).orElse(null), 1.0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AgentAnswer build(RafRequest request, ProcedureDoc proc, SlaDoc sla, MailTemplateDoc template, double score) {
        String lang = request.lang();
        StringBuilder md = new StringBuilder("**📞 ").append(RafText.get(lang, "callcard.title"));
        if (proc != null) md.append(" — ").append(proc.title());
        else if (sla != null) md.append(" — ").append(sla.motif());
        md.append("**\n");
        List<RalphResultItem> citations = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();
        Map<String, Object> payload = new LinkedHashMap<>();

        if (proc != null && !proc.steps().isEmpty()) {
            md.append("\n**🛠 ").append(RafText.get(lang, "callcard.do")).append("**");
            proc.steps().stream().limit(4).forEach(s -> md.append("\n").append(s.number()).append(". ").append(Scoring.truncate(s.content(), 160)));
            citations.add(new RalphResultItem("PROCEDURE", proc.id(), proc.title(), Scoring.truncate(proc.steps().get(0).content(), 160)));
            reasoning.add("étapes : procédure « " + proc.title() + " »");
            payload.put("procedureId", proc.id());
        }
        if (sla != null) {
            Optional<LocalDateTime> due = SlaCalculator.dueDate(request.now(), sla);
            String dueText = due.map(d -> RafText.get(lang, "callcard.scriptDue", RafText.formatDateTime(lang, d))).orElse("");
            md.append("\n\n**💬 ").append(RafText.get(lang, "callcard.say")).append("**\n« ")
                    .append(RafText.get(lang, "callcard.script", sla.slaLabel(), dueText)).append(" »");
            citations.add(new RalphResultItem("SLA", sla.id(), sla.motif(), sla.slaLabel()));
            reasoning.add("délai : règle SLA officielle « " + sla.motif() + " »");
            payload.put("slaLabel", sla.slaLabel());
            due.ifPresent(d -> payload.put("dueAt", d.toString()));
        }
        String route = sla != null && sla.destinationService() != null && !sla.destinationService().isBlank() ? sla.destinationService()
                : proc != null ? proc.responsibleTeam() : null;
        if (route != null && !route.isBlank()) {
            md.append("\n\n**📤 ").append(RafText.get(lang, "callcard.route")).append("** : ").append(route);
        }
        List<RafSuggestion> chips = new ArrayList<>();
        if (template != null) {
            md.append("\n\n**✉ ").append(RafText.get(lang, "callcard.template")).append("** : ").append(template.subject());
            citations.add(new RalphResultItem("MAIL_TEMPLATE", template.id(), template.subject(), template.categoryLabel()));
            chips.add(new RafSuggestion("✉ " + Scoring.truncate(template.subject(), 40), null, "raf:tpl:" + template.id()));
            reasoning.add("modèle : « " + template.subject() + " »");
        }
        if (proc != null) {
            String stepsText = String.join(" ", proc.steps().stream().map(s -> s.content()).toList());
            String haystack = " " + SearchText.normalize(stepsText + " " + proc.title()) + " ";
            List<String> terms = catalog.snapshot().terms().stream()
                    .filter(t -> t.term() != null && t.term().length() >= 2
                            && haystack.contains(" " + SearchText.normalize(t.term()) + " "))
                    .limit(4).map(t -> "**" + t.term() + "** : " + Scoring.truncate(t.definition(), 90)).toList();
            if (!terms.isEmpty()) md.append("\n\n**📖 Termes utiles**\n").append(String.join("\n", terms));
            if (!proc.steps().isEmpty()) {
                chips.add(0, new RafSuggestion("▶ " + RafText.get(lang, "guided.start"), null, "raf:proc:" + proc.id() + ":step:1"));
            }
        }
        return new AgentAnswer(id(), RafIntent.CALL_CARD, score, true, md.toString(), null, citations, chips,
                new RafAction("CALL_CARD", payload), null, reasoning);
    }
}

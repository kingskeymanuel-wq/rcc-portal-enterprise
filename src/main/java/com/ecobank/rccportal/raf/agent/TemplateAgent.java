package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.RafAction;
import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.raf.RafCatalog;
import com.ecobank.rccportal.raf.RafDocs.MailTemplateDoc;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.raf.RafText;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent Modèles — rédige la réponse client à partir d'un modèle de mail OFFICIEL du portail
 * et remplit ses champs [XXX] avec ce que l'agent a donné dans sa phrase (« pour Mme Koné,
 * montant 50 000 FCFA, réf TRX123 »). Les champs non fournis restent visibles à compléter.
 */
@Component
public class TemplateAgent implements RafAgent {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\[([^\\]]{1,40})]");
    private static final Map<String, List<String>> SLOT_ALIASES = Map.of(
            "NOM", List.of("nom", "client", "nom du client", "nom client", "beneficiaire", "civilite nom"),
            "MONTANT", List.of("montant", "somme", "montant de la transaction"),
            "REFERENCE", List.of("reference", "ref", "numero de reference", "numero de transaction", "transaction"),
            "DATE", List.of("date", "date de la transaction", "date de l operation"));

    private final RafCatalog catalog;

    public TemplateAgent(RafCatalog catalog) {
        this.catalog = catalog;
    }

    public String id() { return "template"; }

    public String label() { return "Modèles de mail"; }

    public Set<RafIntent> intents() { return Set.of(RafIntent.TEMPLATE); }

    public Optional<Scoring.Scored<MailTemplateDoc>> best(RafRequest request) {
        return Scoring.rank(request, catalog.snapshot().mailTemplates(), m -> new SearchText.Field[]{
                SearchText.Field.of(m.subject(), 3), SearchText.Field.of(m.categoryLabel(), 2), SearchText.Field.of(m.body(), 0.5)})
                .stream().findFirst();
    }

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        var best = best(request);
        if (best.isEmpty()) return AgentAnswer.notFound(id(), RafIntent.TEMPLATE, "aucun modèle correspondant");
        return draft(request, best.get().doc(), best.get().score());
    }

    @Override
    public AgentAnswer onCommand(RafRequest request, String command) {
        if (!command.startsWith("raf:tpl:")) return null;
        try {
            int id = Integer.parseInt(command.substring("raf:tpl:".length()));
            return catalog.snapshot().mailTemplates().stream().filter(t -> t.id() == id).findFirst()
                    .map(t -> draft(request, t, 1.0)).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    AgentAnswer draft(RafRequest request, MailTemplateDoc t, double score) {
        Map<String, String> slots = request.entities().slotValues();
        List<String> missing = new ArrayList<>();
        String subject = fill(t.subject(), slots, missing);
        String body = fill(t.body(), slots, missing);
        // Le corps complet est affiché une seule fois, dans le brouillon copiable (action DRAFT).
        String md = "**✉ " + subject + "**" + (t.categoryLabel() != null ? " (" + t.categoryLabel() + ")" : "")
                + (missing.isEmpty() ? "" : "\n\n" + RafText.get(request.lang(), "draft.missing", String.join(", ", new LinkedHashSet<>(missing))));
        RafAction action = new RafAction("DRAFT", Map.of("templateId", t.id(), "subject", subject, "body", body,
                "missing", List.copyOf(new LinkedHashSet<>(missing))));
        return new AgentAnswer(id(), RafIntent.TEMPLATE, score, true, md, "**✉ " + subject + "**\n\n" + body,
                List.of(new RalphResultItem("MAIL_TEMPLATE", t.id(), t.subject(), t.categoryLabel())), List.of(), action, null,
                List.of("modèle officiel « " + t.subject() + " »" + (slots.isEmpty() ? "" : ", champs remplis : " + slots.keySet())));
    }

    private static String fill(String text, Map<String, String> slots, List<String> missing) {
        if (text == null) return "";
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = SearchText.normalize(m.group(1));
            String value = null;
            for (var e : SLOT_ALIASES.entrySet()) {
                if (e.getValue().contains(key) && slots.containsKey(e.getKey())) value = slots.get(e.getKey());
            }
            if (value == null) missing.add(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }
}

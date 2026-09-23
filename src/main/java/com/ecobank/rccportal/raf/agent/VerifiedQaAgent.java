package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.raf.RafCatalog;
import com.ecobank.rccportal.raf.RafDocs.VerifiedQaDoc;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Agent Q/R vérifiées — réponses validées par la QA dans la banque d'évaluation. */
@Component
public class VerifiedQaAgent implements RafAgent {

    private final RafCatalog catalog;

    public VerifiedQaAgent(RafCatalog catalog) {
        this.catalog = catalog;
    }

    public String id() { return "verifiedqa"; }

    public String label() { return "Q/R vérifiées par la QA"; }

    public Set<RafIntent> intents() { return Set.of(RafIntent.VERIFIED_QA); }

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        var ranked = Scoring.rank(request, catalog.snapshot().verifiedQa(), q -> new SearchText.Field[]{
                SearchText.Field.of(q.question(), 3), SearchText.Field.of(q.tags(), 2), SearchText.Field.of(q.explanation(), 1)});
        if (ranked.isEmpty()) return AgentAnswer.notFound(id(), RafIntent.VERIFIED_QA, "aucune question vérifiée proche");
        VerifiedQaDoc q = ranked.get(0).doc();
        String md = "**✔ Réponse vérifiée par la QA**\n\n_" + q.question() + "_\n\n**" + q.answer() + "**"
                + (q.explanation() != null && !q.explanation().isBlank() ? "\n\n" + q.explanation() : "");
        return new AgentAnswer(id(), RafIntent.VERIFIED_QA, ranked.get(0).score(), true, md, null,
                List.of(new RalphResultItem("QUIZ", q.id(), q.question(), q.answer())), List.of(), null, null,
                List.of("question vérifiée n°" + q.id()));
    }
}

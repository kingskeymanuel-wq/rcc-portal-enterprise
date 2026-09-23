package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.RafSuggestion;
import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.raf.RafCatalog;
import com.ecobank.rccportal.raf.RafDocs.TermDoc;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Agent Glossaire — sigles et termes bancaires de la table WordTerms (RIB, OTP, DAB...). */
@Component
public class GlossaryAgent implements RafAgent {

    private final RafCatalog catalog;

    public GlossaryAgent(RafCatalog catalog) {
        this.catalog = catalog;
    }

    public String id() { return "glossary"; }

    public String label() { return "Glossaire bancaire"; }

    public Set<RafIntent> intents() { return Set.of(RafIntent.GLOSSARY); }

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        List<TermDoc> terms = catalog.snapshot().terms();
        String stripped = com.ecobank.rccportal.raf.nlp.IntentRouterAccess.stripGlossaryTriggers(request.normalized());
        TermDoc exact = terms.stream().filter(t -> SearchText.normalize(t.term()).equals(stripped)).findFirst().orElse(null);
        double score = 1.0;
        if (exact == null) {
            var ranked = Scoring.rank(request, terms, t -> new SearchText.Field[]{
                    SearchText.Field.of(t.term(), 3), SearchText.Field.of(t.definition(), 1)});
            if (ranked.isEmpty()) return AgentAnswer.notFound(id(), RafIntent.GLOSSARY, "terme absent du glossaire");
            exact = ranked.get(0).doc();
            score = ranked.get(0).score();
        }
        TermDoc t = exact;
        String md = "**📖 " + t.term() + "**" + (t.category() != null ? " (" + t.category() + ")" : "") + "\n\n" + t.definition();
        List<RafSuggestion> related = terms.stream()
                .filter(o -> o.id() != t.id() && t.category() != null && t.category().equals(o.category()))
                .limit(3).map(o -> new RafSuggestion(o.term(), "c'est quoi " + o.term(), null)).toList();
        return new AgentAnswer(id(), RafIntent.GLOSSARY, score, true, md, null,
                List.of(new RalphResultItem("TERM", t.id(), t.term(), Scoring.truncate(t.definition(), 160))),
                related, null, null, List.of("terme « " + t.term() + " » du glossaire"));
    }
}

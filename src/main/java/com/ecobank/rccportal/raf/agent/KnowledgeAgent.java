package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.raf.RafCatalog;
import com.ecobank.rccportal.raf.RafDocs;
import com.ecobank.rccportal.raf.RafModels.*;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Agent Documentation — Base de connaissances et formations (filet de sécurité, toujours consulté). */
@Component
public class KnowledgeAgent implements RafAgent {

    private final RafCatalog catalog;

    public KnowledgeAgent(RafCatalog catalog) {
        this.catalog = catalog;
    }

    public String id() { return "knowledge"; }

    public String label() { return "Base de connaissances & formations"; }

    public Set<RafIntent> intents() { return Set.of(RafIntent.KNOWLEDGE); }

    private record Hit(String type, int id, String title, String text, double score) {
    }

    @Override
    public AgentAnswer answer(RafRequest request, double routerScore) {
        RafDocs.Snapshot data = catalog.snapshot();
        String country = request.entities().countryCode();
        List<Hit> hits = new ArrayList<>();
        for (var s : Scoring.rank(request, data.articles(), a -> new SearchText.Field[]{
                SearchText.Field.of(a.title(), 3), SearchText.Field.of(a.tags(), 2), SearchText.Field.of(a.plainText(), 1)})) {
            double score = s.score();
            if (country != null && s.doc().countryCode() != null) score *= country.equalsIgnoreCase(s.doc().countryCode()) ? 1.2 : 0.6;
            hits.add(new Hit("ARTICLE", s.doc().id(), s.doc().title(), s.doc().plainText(), Math.min(1, score)));
        }
        for (var s : Scoring.rank(request, data.courses(), c -> new SearchText.Field[]{
                SearchText.Field.of(c.title(), 3), SearchText.Field.of(c.category(), 2), SearchText.Field.of(c.description(), 1)})) {
            hits.add(new Hit("COURSE", s.doc().id(), s.doc().title(), s.doc().description(), s.score() * 0.9));
        }
        if (hits.isEmpty()) return AgentAnswer.notFound(id(), RafIntent.KNOWLEDGE, "aucun article ni formation");
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        Hit top = hits.get(0);
        String icon = "ARTICLE".equals(top.type()) ? "📖" : "🎓";
        // Phrases qui répondent vraiment à la question (et non les 450 premiers caractères).
        String md = "**" + icon + " " + top.title() + "**\n\n" + SearchText.bestSentences(top.text(), request.terms(), 3, 500);
        String details = "**" + icon + " " + top.title() + "**\n\n" + SearchText.bestSentences(top.text(), request.terms(), 10, 1800);
        List<RalphResultItem> citations = hits.stream().limit(5)
                .map(h -> new RalphResultItem(h.type(), h.id(), h.title(), SearchText.snippetAround(h.text(), request.terms(), 160)))
                .toList();
        return new AgentAnswer(id(), RafIntent.KNOWLEDGE, top.score(), true, md, details, citations, List.of(), null, null,
                List.of(hits.size() + " document(s) pertinent(s), meilleur : « " + top.title() + " »"));
    }
}

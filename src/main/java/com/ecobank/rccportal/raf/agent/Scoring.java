package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.raf.RafModels.RafRequest;
import com.ecobank.rccportal.util.SearchText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Classement commun aux agents — même moteur de pertinence que les barres de recherche (SearchText). */
final class Scoring {

    private Scoring() {
    }

    record Scored<T>(T doc, double score) {
    }

    /** Documents pertinents, du meilleur au moins bon, avec un score normalisé 0..1. */
    static <T> List<Scored<T>> rank(RafRequest request, List<T> docs, Function<T, SearchText.Field[]> fields) {
        List<Scored<T>> out = new ArrayList<>();
        int n = request.terms().size();
        if (n == 0) return out;
        for (T doc : docs) {
            SearchText.Field[] f = fields.apply(doc);
            SearchText.Match m = SearchText.score(request.question(), request.terms(), f);
            if (!m.isRelevant(n)) continue;
            double maxWeight = 0;
            for (SearchText.Field field : f) maxWeight = Math.max(maxWeight, field.weight());
            out.add(new Scored<>(doc, Math.min(1, m.score() / (n * maxWeight))));
        }
        out.sort(Comparator.comparingDouble((Scored<T> s) -> s.score()).reversed());
        return out;
    }

    static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max).trim() + "…";
    }
}

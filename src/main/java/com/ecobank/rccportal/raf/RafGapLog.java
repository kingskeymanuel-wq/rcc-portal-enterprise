package com.ecobank.rccportal.raf;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Questions auxquelles RAF n'a pas su répondre (déjà assainies) — la QA voit ainsi quels
 * contenus manquent au portail (GET /api/ralph/gaps). RAF s'améliore par le contenu, pas par
 * un modèle d'IA. Tampon circulaire en mémoire (200 dernières).
 */
@Component
public class RafGapLog {

    public record Gap(String question, String bestGuess, LocalDateTime at) {
    }

    private static final int MAX = 200;
    private final Deque<Gap> gaps = new ArrayDeque<>();

    public synchronized void record(String question, String bestGuess) {
        if (question == null || question.isBlank()) return;
        gaps.addFirst(new Gap(question.length() > 300 ? question.substring(0, 300) : question, bestGuess, LocalDateTime.now()));
        while (gaps.size() > MAX) gaps.removeLast();
    }

    public synchronized List<Gap> recent() {
        return new ArrayList<>(gaps);
    }
}

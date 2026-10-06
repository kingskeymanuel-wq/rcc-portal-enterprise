package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.QuizQuestionRequest;
import com.ecobank.rccportal.dto.QuizQuestionResponse;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Évaluation générée à partir d'un support de formation : le texte du document (PDF, Word, TXT) ou du cours est
 * analysé, puis des questions à choix multiple sont rédigées et versées dans la banque de questions de la rubrique
 * (celle du volet « Évaluation » du studio de cours et du Centre d'Évaluation).
 *
 * Avec l'IA (Claude, ou l'IA locale en mode hors ligne — voir AnthropicClient) : questions de compréhension
 * rédigées. Sans IA : questions « phrase à compléter » tirées des phrases clés du document, toujours exactes
 * puisque la bonne réponse est le mot du texte. Chaque question reste modifiable ou supprimable ensuite.
 */
@Slf4j
@Service
public class QuizGenerationService {

    public static final int MAX_QUESTIONS = 30;
    private static final int MAX_TEXT = 30_000;

    public record Draft(String question, List<String> options, int correct, String explanation) {}

    public record Result(String source, int created, List<QuizQuestionResponse> questions, String note) {}

    private final AnthropicClient ai;
    private final QuizQuestionService questions;
    private final DocumentTextExtractionService extraction;
    private final ObjectMapper json = new ObjectMapper();

    public QuizGenerationService(AnthropicClient ai, QuizQuestionService questions, DocumentTextExtractionService extraction) {
        this.ai = ai;
        this.questions = questions;
        this.extraction = extraction;
    }

    /** Texte source : le fichier déposé s'il y en a un, sinon le texte fourni (contenu du cours). */
    public String sourceText(MultipartFile file, String text) {
        String raw = file != null && !file.isEmpty() ? extraction.extractForAnalysis(file) : text;
        String clean = raw == null ? "" : stripHtml(raw).replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        if (clean.length() < 200) {
            throw ApiException.badRequest("Pas assez de texte pour rédiger une évaluation (au moins quelques paragraphes). "
                    + "Joignez le support du cours (PDF, Word ou texte) ou rédigez son contenu.");
        }
        return clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT) : clean;
    }

    public Result generate(MultipartFile file, String text, String category, int count, String difficulty, Long requesterId) {
        String cat = category == null || category.isBlank() ? "Général" : category.trim();
        int n = Math.max(1, Math.min(MAX_QUESTIONS, count));
        String source = sourceText(file, text);

        List<Draft> drafts = null;
        String origin = "local";
        String note = null;
        if (ai.isAvailable()) {
            try {
                drafts = parseAiDrafts(ai.chat(SYSTEM, prompt(source, n), 6000, 120));
                origin = ai.usesLocal() ? "ia-locale" : "ia";
            } catch (RuntimeException e) {
                log.warn("[QUIZ] Génération IA indisponible ({}), repli sur le générateur local", e.getMessage());
                note = "L'IA n'a pas répondu : questions rédigées par le générateur intégré.";
            }
        }
        if (drafts == null || drafts.isEmpty()) {
            drafts = localDrafts(source, n, new Random(source.hashCode()));
            origin = "local";
        }
        if (drafts.isEmpty()) {
            throw ApiException.badRequest("Aucune question n'a pu être tirée de ce texte : il faut des phrases complètes (pas seulement des tableaux ou des titres).");
        }

        String diff = difficulty == null || difficulty.isBlank() ? "MEDIUM" : difficulty.trim().toUpperCase(Locale.ROOT);
        List<QuizQuestionResponse> created = new ArrayList<>();
        for (Draft d : drafts.subList(0, Math.min(n, drafts.size()))) {
            try {
                created.add(questions.create(new QuizQuestionRequest(d.question(), "MCQ", diff, cat, d.options(), d.correct(),
                        null, d.explanation(), null, null, "auto", 10, null, true), requesterId));
            } catch (ApiException e) {
                log.debug("[QUIZ] Question ignorée : {}", e.getMessage());
            }
        }
        if (created.isEmpty()) throw ApiException.badRequest("Les questions rédigées n'ont pas pu être enregistrées.");
        if (created.size() < n && note == null) note = created.size() + " question(s) seulement : le document ne permettait pas d'en tirer davantage.";
        return new Result(origin, created.size(), created, note);
    }

    // ── IA ───────────────────────────────────────────────────────────────────────────

    private static final String SYSTEM = "Tu es formateur au centre de relation client d'Ecobank. À partir du support de formation fourni, "
            + "tu rédiges une évaluation en français : des questions à choix multiple qui vérifient la compréhension des points "
            + "importants (règles, délais, étapes, pièces, conditions, interdits). Chaque question a 4 réponses, une seule exacte, "
            + "les 3 autres plausibles mais fausses. Tu t'appuies UNIQUEMENT sur le texte fourni, sans rien inventer. "
            + "Réponds uniquement par un tableau JSON, sans texte autour : "
            + "[{\"question\":\"…\",\"options\":[\"…\",\"…\",\"…\",\"…\"],\"correct\":0,\"explanation\":\"phrase du support qui justifie\"}]";

    private static String prompt(String text, int n) {
        return "Rédige " + n + " questions différentes, couvrant tout le support (pas seulement le début).\n\nSUPPORT :\n" + text;
    }

    List<Draft> parseAiDrafts(String raw) {
        if (raw == null) return List.of();
        int a = raw.indexOf('['), b = raw.lastIndexOf(']');
        if (a < 0 || b <= a) return List.of();
        List<Draft> out = new ArrayList<>();
        try {
            for (JsonNode q : json.readTree(raw.substring(a, b + 1))) {
                String question = q.path("question").asText("").trim();
                List<String> opts = new ArrayList<>();
                q.path("options").forEach(o -> { String v = o.asText("").trim(); if (!v.isEmpty()) opts.add(v); });
                int correct = q.path("correct").asInt(-1);
                if (question.isEmpty() || question.length() > 1000 || opts.size() < 2 || correct < 0 || correct >= opts.size()) continue;
                if (new HashSet<>(opts).size() != opts.size()) continue;
                out.add(new Draft(question, opts, correct, blankToNull(q.path("explanation").asText(""))));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    // ── Générateur intégré (sans IA, sans Internet) ──────────────────────────────────

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "alors", "aussi", "autre", "autres", "avant", "avoir", "cette", "celle", "celui", "ceux", "chaque", "comme",
            "client", "clients", "conseiller", "dans", "depuis", "doit", "doivent", "donc", "elle", "elles", "encore",
            "entre", "était", "être", "faire", "fait", "leur", "leurs", "lors", "mais", "même", "mêmes", "moins", "notre",
            "nous", "ont", "pour", "plus", "pouvez", "peut", "peuvent", "quand", "quel", "quelle", "quels", "quelles",
            "sans", "selon", "sont", "sous", "suivant", "suivante", "toujours", "tous", "tout", "toute", "toutes", "très",
            "votre", "vous", "avec", "ainsi", "afin", "après", "auprès", "cela", "ceci", "dont", "jamais", "puis", "était",
            "seront", "serait", "ensuite", "pendant", "parce", "lorsque", "lorsqu", "chez", "vers", "contre", "durant",
            "apres", "avant", "ainsi", "egalement", "toutefois", "cependant", "certains", "certaines", "plusieurs", "aucun", "aucune",
            "etre", "etait", "deja", "tres", "meme", "memes", "auront", "devra", "devez", "pouvoir", "sera", "celles"));

    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}\\p{Nd}'’\\-]{3,}");

    /** Questions « phrase à compléter » : la bonne réponse est un terme clé de la phrase, les autres réponses des
     *  termes clés d'autres passages du document. Déterministe pour un même texte. */
    static List<Draft> localDrafts(String text, int n, Random rnd) {
        List<String> sentences = new ArrayList<>();
        for (String s : text.split("(?<=[.!?;:])\\s+|\\n+")) {
            String t = s.trim().replaceAll("^[•\\-*▪●\\d.)\\s]+", "");
            int words = t.split("\\s+").length;
            if (t.length() >= 50 && t.length() <= 260 && words >= 8) sentences.add(t);
        }
        Map<String, Integer> freq = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        for (String s : sentences) {
            var m = WORD.matcher(s);
            while (m.find()) {
                String w = unElide(m.group());
                String k = key(w);
                if (k.length() < 5 || STOP.contains(k) || STOP.contains(w.toLowerCase(Locale.ROOT))) continue;
                freq.merge(k, 1, Integer::sum);
                display.putIfAbsent(k, w);
            }
        }
        // Termes clés : d'abord ceux qui reviennent dans le document (le sujet), puis les mots longs cités une fois.
        List<String> terms = freq.entrySet().stream().filter(e -> e.getValue() >= 2 || e.getKey().length() >= 6)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
        if (terms.size() < 4) return List.of();
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < terms.size(); i++) rank.put(terms.get(i), i);

        record Cand(int index, String sentence, String answerKey, String answerWord, int score) {}
        // Phrases les plus riches en termes clés d'abord ; chacune reçoit le terme le mieux classé qu'elle contient et
        // qui n'a pas déjà servi de réponse — une réponse différente par question.
        record Scored(int index, String sentence, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            int score = 0;
            var m = WORD.matcher(sentences.get(i));
            while (m.find()) {
                Integer r = rank.get(key(unElide(m.group())));
                if (r != null) score += Math.max(1, 40 - r);
            }
            if (score > 0) scored.add(new Scored(i, sentences.get(i), score));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::index));
        Set<String> usedAnswers = new HashSet<>();
        List<Cand> picked = new ArrayList<>();
        for (Scored sc : scored) {
            if (picked.size() >= n) break;
            String best = null, bestWord = null;
            int bestRank = Integer.MAX_VALUE;
            var m = WORD.matcher(sc.sentence());
            while (m.find()) {
                String w = unElide(m.group());
                String k = key(w);
                Integer r = rank.get(k);
                if (r == null || usedAnswers.contains(stem(k))) continue;
                if (freq.get(k) < 2 && k.length() < 7) continue; // réponse : un terme du sujet, pas un mot de passage
                if (r < bestRank) { bestRank = r; best = k; bestWord = w; }
            }
            if (best == null) continue;
            usedAnswers.add(stem(best));
            picked.add(new Cand(sc.index(), sc.sentence(), best, bestWord, sc.score()));
        }
        picked.sort(Comparator.comparingInt(Cand::index));

        List<Draft> out = new ArrayList<>();
        for (Cand c : picked) {
            // Leurres : d'autres termes clés du document (absents de la phrase), jamais une variante de la réponse.
            Set<String> stems = new HashSet<>(List.of(stem(c.answerKey())));
            var sm = WORD.matcher(c.sentence());
            while (sm.find()) stems.add(stem(key(unElide(sm.group())))); // aucun leurre ne reprend un mot de la phrase
            String kind = kind(c.answerWord());
            List<String> pool = new ArrayList<>(), others = new ArrayList<>();
            for (String t : terms) {
                if (!stems.add(stem(t))) continue;
                (kind(display.get(t)).equals(kind) ? pool : others).add(t); // même nature que la réponse (nom, participe, verbe)
            }
            if (pool.size() < 3) pool.addAll(others);
            if (pool.size() < 3) continue;
            int len = c.answerKey().length();
            List<String> head = new ArrayList<>(pool.subList(0, Math.min(8, pool.size())));
            head.sort(Comparator.comparingInt((String t) -> Math.abs(t.length() - len)).thenComparing(t -> rank.get(t)));
            List<String> distractors = new ArrayList<>(head.subList(0, Math.min(5, head.size())));
            Collections.shuffle(distractors, rnd);
            List<String> options = new ArrayList<>();
            options.add(c.answerWord());
            for (String d : distractors.subList(0, 3)) options.add(matchCase(display.get(d), c.answerWord()));
            Collections.shuffle(options, rnd);
            int correct = options.indexOf(c.answerWord());
            String blanked = c.sentence().replaceFirst(Pattern.quote(c.answerWord()), "_____");
            out.add(new Draft("Complétez selon le support : « " + blanked + " »", options, correct, c.sentence()));
        }
        return out;
    }

    /** Nature grossière d'un mot français : participe (-é, -ée…), verbe (-er, -ent, -ait…) ou nom. */
    private static String kind(String w) {
        String l = w == null ? "" : w.toLowerCase(Locale.ROOT);
        if (l.matches(".*(é|ée|és|ées)$")) return "participe";
        if (l.matches(".*(er|ent|ait|aient|ons|ez|ir)$") && !l.matches(".*(ment|dent|cent|gent)$")) return "verbe";
        return "nom";
    }

    /** Racine grossière (6 premières lettres) : « carte » et « cartes », « enregistrée » et « enregistrement » se confondent. */
    private static String stem(String k) {
        String t = k.endsWith("s") || k.endsWith("x") ? k.substring(0, k.length() - 1) : k;
        return t.length() > 6 ? t.substring(0, 6) : t;
    }

    /** « l'appel » → « appel », « d'identité » → « identité » : la réponse est le mot, pas l'article. */
    private static String unElide(String w) {
        return w.replaceFirst("^(?i)(l|d|qu|n|s|j|c|m|t)['’]", "");
    }

    private static String key(String w) {
        return Normalizer.normalize(w.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('’', '\'');
    }

    private static String matchCase(String word, String model) {
        if (word == null || word.isEmpty() || model.isEmpty()) return word;
        boolean upper = Character.isUpperCase(model.charAt(0));
        String first = upper ? word.substring(0, 1).toUpperCase(Locale.ROOT) : word.substring(0, 1).toLowerCase(Locale.ROOT);
        return first + word.substring(1);
    }

    private static String stripHtml(String s) {
        return s.replaceAll("(?i)<br\\s*/?>|</p>|</li>|</h\\d>|</div>", "\n").replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#39;", "'").replace("&quot;", "\"");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

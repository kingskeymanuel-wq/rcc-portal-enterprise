package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.WordTerm;
import com.ecobank.rccportal.repository.WordTermRepository;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.LanguageDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.language.BritishEnglish;
import org.languagetool.language.French;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.spelling.SpellingCheckRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correcteur orthographe/grammaire — LanguageTool, moteur à base de règles et de
 * dictionnaires. AUCUNE dépendance à un LLM/IA (contrainte explicite du projet).
 *
 * <ul>
 *   <li><b>fr / en-US / en-GB</b> : LanguageTool embarqué dans la JVM, aucun appel réseau ;</li>
 *   <li><b>autres langues</b> (es, pt, de, it, nl...) : serveur LanguageTool auto-hébergé
 *       optionnel ({@code rcc.spellcheck.remote-url}), même moteur, même format de réponse ;</li>
 *   <li><b>auto</b> : détection locale de la langue ({@link LanguageDetector}).</li>
 * </ul>
 *
 * <p>Corrections de cohérence apportées :</p>
 * <ul>
 *   <li>{@link JLanguageTool} n'est PAS thread-safe : deux agents qui vérifiaient en même temps
 *       pouvaient obtenir des résultats mélangés ou une exception. Chaque instance est
 *       désormais utilisée sous verrou ;</li>
 *   <li>vocabulaire métier (Ecobank, Rapidtransfer, Xpress, sigles...) et termes de la table
 *       WordTerms ignorés, sigles/numéros/e-mails et noms propres en milieu de phrase non
 *       signalés comme fautes de frappe ;</li>
 *   <li>règles purement stylistiques désactivées (« écrire 3 en lettres », espaces
 *       insécables...) — elles noyaient les vraies fautes ;</li>
 *   <li>erreurs qui se chevauchent dédoublonnées et triées (sinon le surlignage et la liste
 *       de suggestions ne correspondaient plus).</li>
 * </ul>
 */
@Service
public class LocalSpellCheckClient {

    private static final Logger log = LoggerFactory.getLogger(LocalSpellCheckClient.class);

    public static final int MAX_TEXT_LENGTH = 20000;
    private static final long VOCABULARY_REFRESH_MS = 10 * 60 * 1000L;

    private static final Set<String> LOCAL_LANGUAGES = Set.of("fr", "en-US", "en-GB");

    private final ConcurrentHashMap<String, JLanguageTool> instances = new ConcurrentHashMap<>();
    private final WordTermRepository wordTermRepository;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${rcc.spellcheck.remote-url:}")
    private String remoteUrl;
    @Value("${rcc.spellcheck.disabled-rules:}")
    private String disabledRulesConfig;
    @Value("${rcc.spellcheck.ignore-words:}")
    private String ignoreWordsConfig;

    private volatile Set<String> vocabulary = Set.of();
    private volatile long vocabularyLoadedAt = 0;

    public record Issue(
            String message,
            String shortMessage,
            int offset,
            int length,
            List<String> suggestions,
            String ruleId,
            String category,
            String type) {
    }

    public record SpellCheckResult(String language, List<Issue> issues, String engine) {
    }

    public LocalSpellCheckClient(WordTermRepository wordTermRepository) {
        this.wordTermRepository = wordTermRepository;
    }

    public boolean isSupported(String lang) {
        String normalized = normalizeLang(lang);
        return normalized != null && (LOCAL_LANGUAGES.contains(normalized) || "auto".equals(normalized) || remoteConfigured());
    }

    public boolean remoteConfigured() {
        return remoteUrl != null && !remoteUrl.isBlank();
    }

    /**
     * Vérifie un texte et renvoie les erreurs détectées (orthographe, grammaire, ponctuation)
     * avec leurs suggestions — jamais de réécriture automatique : l'agent choisit.
     */
    public SpellCheckResult check(String text, String lang) {
        if (text == null || text.isBlank()) {
            return new SpellCheckResult(lang, List.of(), "LanguageTool");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw ApiException.badRequest("Texte trop long pour le correcteur (" + text.length()
                    + " caractères, maximum " + MAX_TEXT_LENGTH + ").");
        }
        String normalized = normalizeLang(lang);
        if (normalized == null) {
            throw ApiException.badRequest("Langue non reconnue par le correcteur : " + lang + ".");
        }
        if ("auto".equals(normalized)) {
            String detected = LanguageDetector.detect(text);
            String candidate = detected == null ? null : normalizeLang(detected);
            if (candidate != null && LOCAL_LANGUAGES.contains(candidate)) {
                normalized = candidate;
            } else if (remoteConfigured()) {
                return checkRemote(text, "auto");
            } else {
                normalized = candidate != null ? candidate : "fr";
            }
        }

        if (LOCAL_LANGUAGES.contains(normalized)) {
            return checkLocal(text, normalized);
        }
        if (remoteConfigured()) {
            return checkRemote(text, normalized);
        }
        throw ApiException.badRequest("Langue « " + normalized + " » non disponible : seuls le français et l'anglais "
                + "sont embarqués. Pour d'autres langues, l'IT peut configurer un serveur LanguageTool "
                + "(rcc.spellcheck.remote-url).");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Moteur embarqué
    // ══════════════════════════════════════════════════════════════════════

    private SpellCheckResult checkLocal(String text, String lang) {
        JLanguageTool tool = instanceFor(lang);
        refreshVocabularyIfNeeded(tool);
        List<RuleMatch> matches;
        try {
            // JLanguageTool n'est pas thread-safe : une vérification à la fois par instance
            // (quelques dizaines de ms, sans impact perceptible pour les agents).
            synchronized (tool) {
                matches = tool.check(text);
            }
        } catch (java.io.IOException e) {
            throw ApiException.serviceUnavailable("Correcteur local : erreur interne (" + e.getMessage() + ").");
        }
        List<Issue> issues = new ArrayList<>();
        for (RuleMatch m : matches) {
            Rule rule = m.getRule();
            boolean spelling = rule instanceof SpellingCheckRule || rule.isDictionaryBasedSpellingRule();
            int from = m.getFromPos();
            int to = m.getToPos();
            if (spelling && shouldIgnoreSpelling(text, from, to)) continue;
            issues.add(new Issue(
                    m.getMessage(),
                    m.getShortMessage(),
                    from,
                    to - from,
                    m.getSuggestedReplacements().stream().limit(8).toList(),
                    rule.getId(),
                    rule.getCategory() != null ? rule.getCategory().getName() : null,
                    spelling ? "misspelling" : issueType(rule.getLocQualityIssueType() != null
                            ? rule.getLocQualityIssueType().toString() : null)));
        }
        return new SpellCheckResult(lang, clean(issues), "LanguageTool (local)");
    }

    private JLanguageTool instanceFor(String lang) {
        return instances.computeIfAbsent(lang, key -> {
            Language language = switch (key) {
                case "fr" -> new French();
                case "en-GB" -> new BritishEnglish();
                default -> new AmericanEnglish();
            };
            JLanguageTool tool = new JLanguageTool(language);
            List<String> disabled = disabledRules();
            if (!disabled.isEmpty()) tool.disableRules(disabled);
            vocabularyLoadedAt = 0; // force l'ajout du vocabulaire métier à cette nouvelle instance
            return tool;
        });
    }

    /** Ajoute le vocabulaire métier (config + table WordTerms) aux règles d'orthographe. */
    private void refreshVocabularyIfNeeded(JLanguageTool justUsed) {
        long now = System.currentTimeMillis();
        if (now - vocabularyLoadedAt < VOCABULARY_REFRESH_MS) return;
        synchronized (this) {
            if (now - vocabularyLoadedAt < VOCABULARY_REFRESH_MS) return;
            Set<String> words = new LinkedHashSet<>();
            for (String w : splitConfig(ignoreWordsConfig)) addVariants(words, w);
            try {
                for (WordTerm term : wordTermRepository.findByActiveTrueOrderByTermAsc()) {
                    if (term.getTerm() == null) continue;
                    for (String w : term.getTerm().split("[\\s/]+")) addVariants(words, w);
                }
            } catch (Exception e) {
                log.debug("Correcteur : vocabulaire WordTerms indisponible ({}).", e.getMessage());
            }
            vocabulary = Set.copyOf(words);
            List<String> list = new ArrayList<>(words);
            for (JLanguageTool tool : instances.values()) {
                synchronized (tool) {
                    for (Rule rule : tool.getAllActiveRules()) {
                        if (rule instanceof SpellingCheckRule spellingRule) spellingRule.addIgnoreTokens(list);
                    }
                }
            }
            vocabularyLoadedAt = now;
        }
    }

    private static void addVariants(Set<String> words, String w) {
        String word = w.replaceAll("[^\\p{L}\\p{Nd}'’-]", "");
        if (word.isEmpty()) return;
        words.add(word);
        words.add(word.toLowerCase(Locale.ROOT));
        words.add(word.toUpperCase(Locale.ROOT));
        words.add(Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(Locale.ROOT));
    }

    /**
     * Faux positifs classiques d'un correcteur orthographique sur des textes bancaires :
     * sigles (RIB, OTP), références/numéros (TRX123), e-mails, URLs, vocabulaire métier et
     * noms propres (nom du client) en milieu de phrase.
     */
    private boolean shouldIgnoreSpelling(String text, int from, int to) {
        if (from < 0 || to > text.length() || from >= to) return false;
        String token = text.substring(from, to);
        if (vocabulary.contains(token) || vocabulary.contains(token.toLowerCase(Locale.ROOT))) return true;
        if (token.matches(".*\\d.*") || token.contains("@") || token.contains("/") || token.contains("www.")) return true;
        if (token.length() >= 2 && token.equals(token.toUpperCase(Locale.ROOT)) && token.matches(".*\\p{L}.*")) return true;
        if (Character.isUpperCase(token.codePointAt(0)) && !isSentenceStart(text, from)) return true;
        return false;
    }

    private static boolean isSentenceStart(String text, int pos) {
        for (int i = pos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '«' || c == '(' || c == ' ') {
                if (c == '\n') return true;
                continue;
            }
            return c == '.' || c == '!' || c == '?' || c == ':' || c == '…';
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Serveur LanguageTool auto-hébergé (langues non embarquées)
    // ══════════════════════════════════════════════════════════════════════

    private SpellCheckResult checkRemote(String text, String lang) {
        try {
            String base = remoteUrl.replaceAll("/+$", "");
            String url = base.endsWith("/v2/check") ? base : base + "/v2/check";
            StringBuilder form = new StringBuilder()
                    .append("text=").append(URLEncoder.encode(text, StandardCharsets.UTF_8))
                    .append("&language=").append(URLEncoder.encode(lang, StandardCharsets.UTF_8));
            List<String> disabled = disabledRules();
            if (!disabled.isEmpty()) {
                form.append("&disabledRules=").append(URLEncoder.encode(String.join(",", disabled), StandardCharsets.UTF_8));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw ApiException.serviceUnavailable("Serveur LanguageTool : HTTP " + response.statusCode()
                        + " — " + (response.body() != null && response.body().length() > 200 ? response.body().substring(0, 200) : response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            List<Issue> issues = new ArrayList<>();
            for (JsonNode m : root.path("matches")) {
                List<String> suggestions = new ArrayList<>();
                for (JsonNode r : m.path("replacements")) {
                    if (suggestions.size() >= 8) break;
                    suggestions.add(r.path("value").asText());
                }
                int offset = m.path("offset").asInt();
                int length = m.path("length").asInt();
                String type = issueType(m.path("rule").path("issueType").asText(null));
                if ("misspelling".equals(type) && shouldIgnoreSpelling(text, offset, offset + length)) continue;
                issues.add(new Issue(
                        m.path("message").asText(""),
                        m.path("shortMessage").asText(""),
                        offset, length, suggestions,
                        m.path("rule").path("id").asText(null),
                        m.path("rule").path("category").path("name").asText(null),
                        type));
            }
            String detected = root.path("language").path("code").asText(lang);
            return new SpellCheckResult(detected, clean(issues), "LanguageTool (serveur interne)");
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Correcteur interrompu.");
        } catch (Exception e) {
            throw ApiException.serviceUnavailable("Serveur LanguageTool injoignable (" + e.getMessage() + ").");
        }
    }

    // ══════════════════════════════════════════════════════════════════════

    /** Trie par position et retire les chevauchements (garde la première erreur). */
    private static List<Issue> clean(List<Issue> issues) {
        List<Issue> sorted = new ArrayList<>(issues);
        sorted.sort(Comparator.comparingInt(Issue::offset).thenComparing(i -> -i.length()));
        List<Issue> result = new ArrayList<>();
        int cursor = -1;
        for (Issue issue : sorted) {
            if (issue.length() <= 0 || issue.offset() < cursor) continue;
            result.add(issue);
            cursor = issue.offset() + issue.length();
        }
        return result;
    }

    private static String issueType(String raw) {
        if (raw == null) return "other";
        String t = raw.toLowerCase(Locale.ROOT);
        if (t.contains("misspelling")) return "misspelling";
        if (t.contains("grammar")) return "grammar";
        if (t.contains("typographical") || t.contains("whitespace") || t.contains("punctuation")) return "typographical";
        if (t.contains("style") || t.contains("register")) return "style";
        return "other";
    }

    private List<String> disabledRules() {
        return splitConfig(disabledRulesConfig);
    }

    private static List<String> splitConfig(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** fr, en-US, en-GB, auto, ou code ISO simple (es, pt...) pour le serveur distant. */
    static String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) return null;
        String l = lang.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (l.equals("auto")) return "auto";
        if (l.startsWith("fr")) return "fr";
        if (l.equals("en-gb") || l.equals("en-uk")) return "en-GB";
        if (l.startsWith("en")) return "en-US";
        if (l.matches("[a-z]{2,3}(-[a-z]{2})?")) {
            int dash = l.indexOf('-');
            return dash > 0 ? l.substring(0, dash) + "-" + l.substring(dash + 1).toUpperCase(Locale.ROOT) : l;
        }
        return null;
    }
}

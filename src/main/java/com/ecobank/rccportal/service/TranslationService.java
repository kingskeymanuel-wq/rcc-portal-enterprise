package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.LanguageDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traducteur du portail (/translator et bouton « Traduire » de RAF) — aucune IA générative,
 * uniquement des moteurs de traduction dédiés, essayés dans l'ordre configuré
 * ({@code rcc.translation.provider-order}) jusqu'au premier qui répond correctement :
 * <ol>
 *   <li><b>libretranslate</b> — LibreTranslate local (serveur HTTP qui s'appuie sur le moteur
 *       Argos Translate, modèles chargés une fois en mémoire : rapide, hors-ligne, détection
 *       auto) — <b>chemin principal</b> : Application → TranslationService → LibreTranslate
 *       local → Argos Translate → traduction ;</li>
 *   <li><b>local</b> — Argos Translate appelé directement (scripts/translate.py), secours si
 *       le serveur LibreTranslate est arrêté ;</li>
 *   <li><b>custom</b> — point d'entrée interne Ecobank (contrat JSON historique) ;</li>
 *   <li><b>deepl</b> — DeepL API (clé gratuite « :fx » ou Pro) ;</li>
 *   <li><b>azure</b> — Azure AI Translator (cohérent avec le reste de la pile Azure du portail,
 *       couvre haoussa, yoruba, igbo, swahili, lingala, somali...) ;</li>
 *   <li><b>mymemory</b> — MyMemory (gratuit, sans clé, qualité variable : dernier recours).</li>
 * </ol>
 *
 * <p>Corrections de cohérence par rapport à l'ancienne implémentation (dans RalphSearchService) :</p>
 * <ul>
 *   <li>« Détecter la langue » (choix par défaut) échouait systématiquement avec MyMemory :
 *       détection locale via {@link LanguageDetector} ;</li>
 *   <li>MyMemory renvoie HTTP 200 même en cas d'erreur (quota dépassé, paire invalide) avec le
 *       message d'erreur dans {@code translatedText} — ce message était affiché à l'agent comme
 *       « traduction ». Il est maintenant détecté et la source suivante est essayée ;</li>
 *   <li>entités HTML (&amp;#39;) non décodées, sauts de ligne perdus, découpage en caractères
 *       au lieu d'octets (limite MyMemory = 500 octets) ;</li>
 *   <li>résultats différents pour un même texte d'un appel à l'autre : cache LRU ;</li>
 *   <li>la réponse indique maintenant quel moteur a traduit ({@code provider}).</li>
 * </ul>
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    public static final int MAX_TEXT_LENGTH = 5000;

    private static final Pattern MYMEMORY_ERROR = Pattern.compile(
            "MYMEMORY WARNING|QUERY LENGTH LIMIT|INVALID LANGUAGE PAIR|PLEASE SELECT TWO DISTINCT LANGUAGES|"
                    + "IS AN INVALID TARGET LANGUAGE|IS AN INVALID SOURCE LANGUAGE|NO QUERY SPECIFIED|AUTO-DETECTION",
            Pattern.CASE_INSENSITIVE);

    /** Langues acceptées par DeepL (codes source, sans variante régionale). */
    private static final Set<String> DEEPL_LANGUAGES = Set.of(
            "ar", "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hu", "id", "it", "ja", "ko",
            "lt", "lv", "nb", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "zh");

    public record TranslationResult(String translatedText, String detectedSourceLang, String provider) {
    }

    private record CacheKey(String source, String target, String text) {
    }

    /** Une source de traduction. {@code nativeAutoDetect} : sait détecter la langue elle-même. */
    private interface Provider {
        String id();

        String label();

        boolean isConfigured();

        String notConfiguredHint();

        boolean nativeAutoDetect();

        TranslationResult translate(String text, String source, String target) throws Exception;
    }

    private final LocalTranslationClient localTranslationClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${rcc.translation.provider-order:libretranslate,local,custom,deepl,azure,mymemory}")
    private String providerOrder;
    @Value("${rcc.translation.timeout-seconds:12}")
    private int timeoutSeconds;
    @Value("${rcc.translation.custom-url:}")
    private String customUrl;
    @Value("${rcc.translation.libretranslate.url:}")
    private String libreUrl;
    @Value("${rcc.translation.libretranslate.api-key:}")
    private String libreApiKey;
    @Value("${rcc.translation.deepl.api-key:}")
    private String deeplKey;
    @Value("${rcc.translation.deepl.url:}")
    private String deeplUrl;
    @Value("${rcc.translation.azure.key:}")
    private String azureKey;
    @Value("${rcc.translation.azure.region:}")
    private String azureRegion;
    @Value("${rcc.translation.azure.endpoint:https://api.cognitive.microsofttranslator.com}")
    private String azureEndpoint;
    @Value("${rcc.translation.mymemory.enabled:true}")
    private boolean myMemoryEnabled;
    @Value("${rcc.translation.mymemory.email:}")
    private String myMemoryEmail;
    @Value("${rcc.translation.cache-size:500}")
    private int cacheSize;

    private final Map<CacheKey, TranslationResult> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, TranslationResult> eldest) {
            return size() > Math.max(0, cacheSize);
        }
    };

    /** Durée pendant laquelle une source injoignable est ignorée avant un nouvel essai. */
    private static final long UNREACHABLE_RETRY_MS = 2 * 60 * 1000L;

    /** Sources injoignables (serveur arrêté, domaine bloqué) → instant du prochain essai. */
    private final Map<String, Long> unreachableUntil = new java.util.concurrent.ConcurrentHashMap<>();

    public TranslationService(LocalTranslationClient localTranslationClient) {
        this.localTranslationClient = localTranslationClient;
    }

    /** Erreur réseau (connexion refusée, délai de connexion, DNS) — pas une erreur de traduction. */
    static boolean isUnreachable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.http.HttpConnectTimeoutException
                    || t instanceof java.net.UnknownHostException || t instanceof java.net.NoRouteToHostException) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains("serveur injoignable")) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    // API publique
    // ══════════════════════════════════════════════════════════════════════

    public TranslationResult translate(String text, String sourceLang, String targetLang) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("Le texte à traduire ne peut pas être vide.");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw ApiException.badRequest("Texte trop long (" + text.length() + " caractères, maximum " + MAX_TEXT_LENGTH + ").");
        }
        String target = canonical(targetLang);
        if (target == null || "auto".equals(target)) target = "fr";
        String source = canonical(sourceLang);
        if (source == null) source = "auto";

        String detected = "auto".equals(source) ? LanguageDetector.detect(text) : source;
        if (detected != null && baseCode(detected).equals(baseCode(target))) {
            // Déjà dans la langue cible : rien à traduire (évite un aller-retour réseau qui
            // renvoyait parfois un texte légèrement « reformulé », source d'incohérence).
            return new TranslationResult(text, detected, "Aucun (texte déjà dans la langue cible)");
        }

        CacheKey key = new CacheKey(source, target, text);
        synchronized (cache) {
            TranslationResult cached = cache.get(key);
            if (cached != null) return cached;
        }

        // Sources utilisables pour ce texte, dans l'ordre de préférence.
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Provider> candidates = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (Provider provider : orderedProviders()) {
            if (!provider.isConfigured()) continue;
            Long retryAt = unreachableUntil.get(provider.id());
            if (retryAt != null && retryAt > System.currentTimeMillis()) {
                // Source injoignable/trop lente il y a peu : on ne fait pas attendre l'agent un nouveau délai.
                failures.add(provider.label() + " : indisponible (nouvel essai dans "
                        + Math.max(1, (retryAt - System.currentTimeMillis()) / 1000) + " s)");
                continue;
            }
            String sourceForCall;
            if (!"auto".equals(source)) {
                sourceForCall = source;
            } else if (provider.nativeAutoDetect()) {
                sourceForCall = "auto";
            } else if (detected != null) {
                sourceForCall = detected;
            } else {
                failures.add(provider.label() + " : langue source non détectable automatiquement pour ce texte");
                continue;
            }
            candidates.add(provider);
            sources.add(sourceForCall);
        }

        // Course « relais » : la source préférée part seule ; si elle n'a pas répondu en
        // HEDGE_DELAY_MS (ou échoue), la suivante part EN PARALLÈLE et la première bonne réponse
        // gagne. Avant : chaque source lente faisait attendre jusqu'au délai complet (12 s) avant
        // d'essayer la suivante — d'où des traductions de 20-30 s.
        TranslationResult won = race(text, target, detected, candidates, sources, failures);
        if (won != null) {
            synchronized (cache) {
                cache.put(key, won);
            }
            return won;
        }

        // Dernier recours hors-ligne : formules courantes de la relation client.
        String phrase = CommonPhrases.lookup(text, "auto".equals(source) ? detected : source, target);
        if (phrase != null) {
            return new TranslationResult(phrase, "auto".equals(source) ? detected : source, "Glossaire RCC (hors-ligne)");
        }

        if (failures.isEmpty()) {
            throw ApiException.serviceUnavailable("Aucune source de traduction n'est configurée sur ce serveur. "
                    + "Configurez au moins l'une des sources rcc.translation.* (voir docs/TRANSLATION_OFFLINE_SETUP.md).");
        }
        String hint = "auto".equals(source) && detected == null
                ? " Astuce : choisissez explicitement la langue source au lieu de « Détecter la langue »."
                : "";
        throw ApiException.serviceUnavailable("La traduction a échoué sur toutes les sources disponibles — "
                + String.join(" ; ", failures) + "." + hint);
    }

    /** Délai avant de lancer la source suivante en parallèle quand la précédente tarde. */
    static final long HEDGE_DELAY_MS = 2500;
    /** Source trop lente (délai dépassé) : écartée un moment pour ne pas ralentir les agents. */
    private static final long SLOW_RETRY_MS = 60 * 1000L;

    private static final java.util.concurrent.ExecutorService RACE_POOL = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "translate-race");
        t.setDaemon(true);
        return t;
    });

    private record Attempt(Provider provider, String source, TranslationResult result, Exception error) {
    }

    private TranslationResult race(String text, String target, String detected, List<Provider> candidates,
                                   List<String> sources, List<String> failures) {
        if (candidates.isEmpty()) return null;
        java.util.concurrent.ExecutorCompletionService<Attempt> ecs = new java.util.concurrent.ExecutorCompletionService<>(RACE_POOL);
        List<java.util.concurrent.Future<Attempt>> running = new ArrayList<>();
        int launched = 0;
        int finished = 0;
        long deadline = System.currentTimeMillis() + (Math.max(1, timeoutSeconds) + 5) * 1000L;
        try {
            running.add(submit(ecs, candidates.get(0), sources.get(0), text, target));
            launched = 1;
            while (finished < launched) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                long wait = launched < candidates.size() ? Math.min(HEDGE_DELAY_MS, left) : left;
                java.util.concurrent.Future<Attempt> done = ecs.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (done == null) {
                    if (launched < candidates.size()) {
                        running.add(submit(ecs, candidates.get(launched), sources.get(launched), text, target));
                        launched++;
                    }
                    continue;
                }
                finished++;
                Attempt a = done.get();
                if (a.error() == null) {
                    unreachableUntil.remove(a.provider().id());
                    TranslationResult r = a.result();
                    String detectedLang = r.detectedSourceLang() != null && !r.detectedSourceLang().isBlank()
                            ? canonical(r.detectedSourceLang())
                            : ("auto".equals(a.source()) ? detected : a.source());
                    return new TranslationResult(r.translatedText(), detectedLang, a.provider().label());
                }
                recordFailure(a, target, failures);
                if (launched < candidates.size()) {
                    running.add(submit(ecs, candidates.get(launched), sources.get(launched), text, target));
                    launched++;
                }
            }
            for (int i = 0; i < launched; i++) {
                if (!running.get(i).isDone()) failures.add(candidates.get(i).label() + " : aucune réponse dans le délai imparti");
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            failures.add(String.valueOf(e.getCause()));
            return null;
        } finally {
            running.forEach(f -> f.cancel(true)); // les requêtes perdantes sont abandonnées
        }
    }

    private java.util.concurrent.Future<Attempt> submit(java.util.concurrent.ExecutorCompletionService<Attempt> ecs,
                                                        Provider provider, String sourceForCall, String text, String target) {
        return ecs.submit(() -> {
            try {
                TranslationResult result = provider.translate(text, sourceForCall, target);
                if (result == null || result.translatedText() == null || result.translatedText().isBlank()) {
                    throw new IllegalStateException("réponse vide");
                }
                return new Attempt(provider, sourceForCall, result, null);
            } catch (Exception e) {
                return new Attempt(provider, sourceForCall, null, e);
            }
        });
    }

    private void recordFailure(Attempt a, String target, List<String> failures) {
        Exception e = a.error();
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (isUnreachable(e)) {
            unreachableUntil.put(a.provider().id(), System.currentTimeMillis() + UNREACHABLE_RETRY_MS);
            if (e.getMessage() == null) {
                reason = "injoignable depuis ce serveur (réseau ou pare-feu, " + e.getClass().getSimpleName() + ")";
            }
        } else if (isTimeout(e)) {
            unreachableUntil.put(a.provider().id(), System.currentTimeMillis() + SLOW_RETRY_MS);
            reason = "trop lente (aucune réponse en " + timeoutSeconds + " s)";
        } else {
            unreachableUntil.remove(a.provider().id());
        }
        log.warn("[TRANSLATE] {} a échoué ({} → {}) : {}", a.provider().label(), a.source(), target, reason);
        failures.add(a.provider().label() + " : " + reason);
    }

    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException) return true;
        }
        return false;
    }

    /** Teste chaque source indépendamment — bouton « Diagnostiquer la connexion ». */
    public List<Map<String, String>> diagnose() {
        List<Map<String, String>> report = new ArrayList<>();
        String probe = "Bonjour, votre carte est prête.";
        for (Provider provider : orderedProviders()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("source", provider.label());
            if (!provider.isConfigured()) {
                row.put("status", "NON CONFIGURÉ");
                row.put("detail", provider.notConfiguredHint());
            } else {
                long start = System.currentTimeMillis();
                try {
                    TranslationResult r = provider.translate(probe, "fr", "en");
                    unreachableUntil.remove(provider.id()); // de nouveau joignable : réutilisée tout de suite
                    row.put("status", "OK");
                    String detail = "« " + r.translatedText() + " » en " + (System.currentTimeMillis() - start) + " ms.";
                    if ("libretranslate".equals(provider.id())) {
                        List<String> langs = libreLanguages();
                        if (!langs.isEmpty()) detail += " Langues chargées : " + String.join(", ", langs) + ".";
                    }
                    row.put("detail", detail);
                } catch (Exception e) {
                    row.put("status", "ÉCHEC");
                    row.put("detail", e.getClass().getSimpleName() + " : " + e.getMessage());
                }
            }
            report.add(row);
        }
        return report;
    }

    /** Sources actives, dans l'ordre d'essai — affiché dans l'interface. */
    public List<String> activeProviders() {
        return orderedProviders().stream().filter(Provider::isConfigured).map(Provider::label).toList();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Sources
    // ══════════════════════════════════════════════════════════════════════

    private List<Provider> orderedProviders() {
        Map<String, Provider> all = new LinkedHashMap<>();
        for (Provider p : List.of(localProvider(), customProvider(), libreProvider(), deeplProvider(),
                azureProvider(), myMemoryProvider())) {
            all.put(p.id(), p);
        }
        List<Provider> ordered = new ArrayList<>();
        if (providerOrder != null) {
            for (String id : providerOrder.split(",")) {
                Provider p = all.remove(id.trim().toLowerCase(Locale.ROOT));
                if (p != null) ordered.add(p);
            }
        }
        ordered.addAll(all.values()); // sources non citées : à la fin, jamais oubliées
        return ordered;
    }

    private Provider localProvider() {
        return new Provider() {
            public String id() { return "local"; }
            public String label() { return "Argos Translate (local, hors-ligne)"; }
            public boolean isConfigured() { return localTranslationClient.isConfigured(); }
            public String notConfiguredHint() {
                return "rcc.translation.offline.python-executable / script-path vides — voir docs/TRANSLATION_OFFLINE_SETUP.md.";
            }
            public boolean nativeAutoDetect() { return false; }
            public TranslationResult translate(String text, String source, String target) {
                var r = localTranslationClient.translate(text, source, target);
                return new TranslationResult(r.translatedText(), r.detectedSourceLang(), label());
            }
        };
    }

    private Provider customProvider() {
        return new Provider() {
            public String id() { return "custom"; }
            public String label() { return "Service interne Ecobank"; }
            public boolean isConfigured() { return notBlank(customUrl); }
            public String notConfiguredHint() { return "rcc.translation.custom-url vide."; }
            public boolean nativeAutoDetect() { return false; }
            public TranslationResult translate(String text, String source, String target) throws Exception {
                String json = objectMapper.writeValueAsString(Map.of("text", text, "sourceLang", source, "targetLang", target));
                HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(customUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
                JsonNode root = objectMapper.readTree(response.body());
                return new TranslationResult(root.path("translatedText").asText(null), null, label());
            }
        };
    }

    private Provider libreProvider() {
        return new Provider() {
            public String id() { return "libretranslate"; }
            public String label() { return "LibreTranslate (local)"; }
            public boolean isConfigured() { return notBlank(libreUrl); }
            public String notConfiguredHint() {
                return "rcc.translation.libretranslate.url vide — démarrez LibreTranslate (scripts/start-libretranslate.ps1) "
                        + "et renseignez http://localhost:5000.";
            }
            public boolean nativeAutoDetect() { return true; }
            public TranslationResult translate(String text, String source, String target) throws Exception {
                Map<String, String> body = new LinkedHashMap<>();
                body.put("q", text);
                body.put("source", "auto".equals(source) ? "auto" : libreCode(source));
                body.put("target", libreCode(target));
                body.put("format", "text");
                if (notBlank(libreApiKey)) body.put("api_key", libreApiKey);
                HttpRequest request = HttpRequest.newBuilder(URI.create(libreBase() + "/translate"))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response;
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } catch (java.net.ConnectException e) {
                    throw new IllegalStateException("serveur injoignable sur " + libreBase()
                            + " — LibreTranslate est-il démarré ? (scripts/start-libretranslate.ps1)");
                }
                JsonNode root = response.body() == null || response.body().isBlank()
                        ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
                if (response.statusCode() / 100 != 2 || root.hasNonNull("error")) {
                    // Ex. 400 {"error":"ha is not supported"} : paire non chargée au démarrage (--load-only).
                    String error = root.path("error").asText("HTTP " + response.statusCode());
                    throw new IllegalStateException(error + (response.statusCode() == 400
                            ? " (langue non chargée dans LibreTranslate — voir LT_LOAD_ONLY dans scripts/start-libretranslate.ps1)" : ""));
                }
                String detected = root.path("detectedLanguage").path("language").asText(null);
                return new TranslationResult(root.path("translatedText").asText(null), detected, label());
            }
        };
    }

    private String libreBase() {
        return libreUrl.trim().replaceAll("/+$", "").replaceAll("/translate$", "");
    }

    /** Langues chargées par le serveur LibreTranslate (GET /languages) — affichées par le diagnostic. */
    List<String> libreLanguages() {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(libreBase() + "/languages")).GET());
            List<String> codes = new ArrayList<>();
            for (JsonNode lang : objectMapper.readTree(response.body())) codes.add(lang.path("code").asText());
            return codes;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Provider deeplProvider() {
        return new Provider() {
            public String id() { return "deepl"; }
            public String label() { return "DeepL"; }
            public boolean isConfigured() { return notBlank(deeplKey); }
            public String notConfiguredHint() { return "rcc.translation.deepl.api-key vide."; }
            public boolean nativeAutoDetect() { return true; }
            public TranslationResult translate(String text, String source, String target) throws Exception {
                if (!DEEPL_LANGUAGES.contains(baseCode(target))
                        || (!"auto".equals(source) && !DEEPL_LANGUAGES.contains(baseCode(source)))) {
                    throw new UnsupportedOperationException("paire de langues non prise en charge par DeepL");
                }
                String url = notBlank(deeplUrl) ? deeplUrl
                        : (deeplKey.trim().endsWith(":fx") ? "https://api-free.deepl.com/v2/translate" : "https://api.deepl.com/v2/translate");
                StringBuilder form = new StringBuilder()
                        .append("text=").append(URLEncoder.encode(text, StandardCharsets.UTF_8))
                        .append("&target_lang=").append(deeplTarget(target))
                        .append("&preserve_formatting=1");
                if (!"auto".equals(source)) form.append("&source_lang=").append(baseCode(source).toUpperCase(Locale.ROOT));
                HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "DeepL-Auth-Key " + deeplKey.trim())
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8)));
                JsonNode first = objectMapper.readTree(response.body()).path("translations").path(0);
                String detected = first.path("detected_source_language").asText(null);
                return new TranslationResult(first.path("text").asText(null),
                        detected != null ? detected.toLowerCase(Locale.ROOT) : null, label());
            }
        };
    }

    private Provider azureProvider() {
        return new Provider() {
            public String id() { return "azure"; }
            public String label() { return "Azure AI Translator"; }
            public boolean isConfigured() { return notBlank(azureKey); }
            public String notConfiguredHint() { return "rcc.translation.azure.key / region vides (ressource « Translator » Azure)."; }
            public boolean nativeAutoDetect() { return true; }
            public TranslationResult translate(String text, String source, String target) throws Exception {
                String url = azureEndpoint.replaceAll("/+$", "") + "/translate?api-version=3.0&to="
                        + URLEncoder.encode(azureCode(target), StandardCharsets.UTF_8)
                        + ("auto".equals(source) ? "" : "&from=" + URLEncoder.encode(azureCode(source), StandardCharsets.UTF_8));
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .header("Ocp-Apim-Subscription-Key", azureKey.trim())
                        .header("Content-Type", "application/json; charset=UTF-8");
                if (notBlank(azureRegion)) builder.header("Ocp-Apim-Subscription-Region", azureRegion.trim());
                String body = objectMapper.writeValueAsString(List.of(Map.of("Text", text)));
                HttpResponse<String> response = send(builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
                JsonNode first = objectMapper.readTree(response.body()).path(0);
                String detected = first.path("detectedLanguage").path("language").asText(null);
                return new TranslationResult(first.path("translations").path(0).path("text").asText(null), detected, label());
            }
        };
    }

    private Provider myMemoryProvider() {
        return new Provider() {
            public String id() { return "mymemory"; }
            public String label() { return "MyMemory"; }
            public boolean isConfigured() { return myMemoryEnabled; }
            public String notConfiguredHint() { return "Désactivé (rcc.translation.mymemory.enabled=false)."; }
            public boolean nativeAutoDetect() { return false; }
            public TranslationResult translate(String text, String source, String target) throws Exception {
                // Paragraphe par paragraphe pour conserver la mise en forme (sauts de ligne).
                String[] lines = text.split("\n", -1);
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) out.append('\n');
                    String line = lines[i];
                    if (line.isBlank()) {
                        out.append(line);
                        continue;
                    }
                    List<String> parts = new ArrayList<>();
                    for (String chunk : splitByBytes(line.strip(), 480)) parts.add(myMemoryChunk(chunk, source, target));
                    out.append(String.join(" ", parts));
                }
                return new TranslationResult(out.toString(), null, label());
            }
        };
    }

    private String myMemoryChunk(String chunk, String source, String target) throws Exception {
        String url = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(chunk, StandardCharsets.UTF_8)
                + "&langpair=" + URLEncoder.encode(source + "|" + target, StandardCharsets.UTF_8)
                + (notBlank(myMemoryEmail) ? "&de=" + URLEncoder.encode(myMemoryEmail.trim(), StandardCharsets.UTF_8) : "");
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url)).GET());
        JsonNode root = objectMapper.readTree(response.body());
        int status = root.path("responseStatus").asInt(200);
        String translated = root.path("responseData").path("translatedText").asText(null);
        if (status != 200) {
            String details = root.path("responseDetails").asText(translated != null ? translated : "");
            throw new IllegalStateException("MyMemory a répondu " + status + " — " + details);
        }
        if (translated == null || translated.isBlank()) throw new IllegalStateException("réponse vide");
        if (MYMEMORY_ERROR.matcher(translated).find()) {
            // Message d'erreur déguisé en traduction (quota journalier atteint, paire invalide...).
            throw new IllegalStateException(translated);
        }
        return unescapeHtml(translated);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Utilitaires
    // ══════════════════════════════════════════════════════════════════════

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        HttpResponse<String> response = httpClient.send(builder.timeout(Duration.ofSeconds(timeoutSeconds)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            throw new IllegalStateException("HTTP " + response.statusCode()
                    + (body.isBlank() ? "" : " — " + (body.length() > 200 ? body.substring(0, 200) + "…" : body)));
        }
        return response;
    }

    /** Code langue canonique : minuscules, sauf variantes régionales connues (zh-CN, zh-TW). */
    static String canonical(String lang) {
        if (lang == null || lang.isBlank()) return null;
        String l = lang.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return switch (l) {
            case "zh", "zh-cn", "zh-hans" -> "zh-CN";
            case "zh-tw", "zh-hant" -> "zh-TW";
            case "iw" -> "he";
            default -> l.contains("-") ? l.substring(0, l.indexOf('-')) : l;
        };
    }

    private static String baseCode(String lang) {
        String l = lang.toLowerCase(Locale.ROOT);
        int dash = l.indexOf('-');
        return dash > 0 ? l.substring(0, dash) : l;
    }

    private static String libreCode(String lang) {
        return "zh-TW".equals(lang) ? "zt" : baseCode(lang);
    }

    private static String azureCode(String lang) {
        return switch (lang) {
            case "zh-CN" -> "zh-Hans";
            case "zh-TW" -> "zh-Hant";
            default -> lang;
        };
    }

    private static String deeplTarget(String lang) {
        return switch (baseCode(lang)) {
            case "en" -> "EN-GB";
            case "pt" -> "PT-PT";
            case "zh" -> "ZH-HANS";
            default -> baseCode(lang).toUpperCase(Locale.ROOT);
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Découpe sur les fins de phrase puis sur les espaces, chaque morceau ≤ maxBytes octets UTF-8. */
    static List<String> splitByBytes(String text, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        if (utf8Length(text) <= maxBytes) {
            chunks.add(text);
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String sentence : text.split("(?<=[.!?;:])\\s+")) {
            for (String piece : utf8Length(sentence) <= maxBytes ? List.of(sentence) : splitWords(sentence, maxBytes)) {
                String candidate = current.length() == 0 ? piece : current + " " + piece;
                if (utf8Length(candidate) > maxBytes && current.length() > 0) {
                    chunks.add(current.toString());
                    current = new StringBuilder(piece);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    private static List<String> splitWords(String sentence, int maxBytes) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : sentence.split("\\s+")) {
            // Mot isolé plus long que la limite (URL...) : coupé net plutôt que rejeté par l'API.
            while (utf8Length(word) > maxBytes) {
                int cut = Math.min(word.length(), maxBytes / 4);
                pieces.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (utf8Length(candidate) > maxBytes && current.length() > 0) {
                pieces.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) pieces.add(current.toString());
        return pieces;
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    static String unescapeHtml(String s) {
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
    }
}

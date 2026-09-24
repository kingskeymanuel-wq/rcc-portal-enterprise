package com.ecobank.rccportal.service;

import com.ecobank.rccportal.config.WebSearchProperties;
import com.ecobank.rccportal.dto.WebSearchResultItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Client de recherche web — utilisé par la barre de recherche globale et par RAF UNIQUEMENT
 * en complément, quand la Base de connaissances / les procédures internes ne suffisent pas.
 * Les résultats web sont toujours présentés comme une source externe.
 *
 * <p>Plusieurs moteurs (voir {@link WebSearchProperties}) essayés dans l'ordre configuré
 * jusqu'au premier qui renvoie des résultats : SearXNG (auto-hébergé), Brave Search, Tavily,
 * Google Programmable Search, Bing (API retirée, compatibilité) et Wikipédia (gratuit, sans
 * clé). L'ancienne version ne connaissait que Bing Web Search, retirée par Microsoft en
 * août 2025 : la recherche web ne renvoyait donc plus jamais rien.</p>
 *
 * <p>Jamais d'exception vers l'appelant : en cas d'échec, liste vide et log explicite.</p>
 */
@Service
public class WebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(WebSearchClient.class);
    private static final String USER_AGENT = "RCC-Portal/1.0 (Ecobank RCC; recherche interne)";

    private final WebSearchProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record CacheEntry(long expiresAt, List<WebSearchResultItem> results) {
    }

    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > 200;
        }
    };

    /** Moteurs injoignables (serveur sans Internet, pare-feu) → instant du prochain essai. */
    private final Map<String, Long> unreachableUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long UNREACHABLE_RETRY_MS = 2 * 60 * 1000L;

    static boolean isUnreachable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.http.HttpConnectTimeoutException
                    || t instanceof java.net.UnknownHostException || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.http.HttpTimeoutException) return true;
        }
        return false;
    }

    private boolean skipped(String provider) {
        Long until = unreachableUntil.get(provider);
        return until != null && until > System.currentTimeMillis();
    }

    private void markFailure(String provider, Exception e) {
        if (isUnreachable(e)) unreachableUntil.put(provider, System.currentTimeMillis() + UNREACHABLE_RETRY_MS);
    }

    public WebSearchClient(WebSearchProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled() && orderedProviders().stream().anyMatch(this::isProviderConfigured);
    }

    /**
     * Recherche sur le web. Retourne une liste vide (jamais d'exception) en cas d'échec — le
     * repli web est une amélioration best-effort, pas un point de blocage.
     */
    public List<WebSearchResultItem> search(String query) {
        if (!isConfigured()) {
            log.debug("Recherche web : non configurée (websearch.enabled / clés), ignorée.");
            return List.of();
        }
        if (query == null || query.isBlank()) return List.of();

        String cacheKey = query.trim().toLowerCase(Locale.ROOT);
        synchronized (cache) {
            CacheEntry entry = cache.get(cacheKey);
            if (entry != null && entry.expiresAt() > System.currentTimeMillis()) return entry.results();
        }

        for (String provider : orderedProviders()) {
            if (!isProviderConfigured(provider) || skipped(provider)) continue; // injoignable il y a peu : pas de nouvelle attente
            try {
                List<WebSearchResultItem> results = callProvider(provider, query.trim());
                if (!results.isEmpty()) {
                    log.debug("Recherche web ({}) : {} résultat(s) pour « {} ».", provider, results.size(), query);
                    if (properties.getCacheMinutes() > 0) {
                        synchronized (cache) {
                            cache.put(cacheKey, new CacheEntry(
                                    System.currentTimeMillis() + properties.getCacheMinutes() * 60_000L, results));
                        }
                    }
                    return results;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (Exception e) {
                markFailure(provider, e);
                log.warn("Recherche web : {} a échoué pour « {} » : {}", provider, query, e.getMessage());
            }
        }
        return List.of();
    }

    /** Teste chaque moteur indépendamment — pour l'IT (distingue clé invalide / blocage réseau). */
    public List<Map<String, String>> diagnose() {
        List<Map<String, String>> report = new ArrayList<>();
        for (String provider : orderedProviders()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("source", provider);
            if (!properties.isEnabled()) {
                row.put("status", "DÉSACTIVÉ");
                row.put("detail", "websearch.enabled=false");
            } else if (!isProviderConfigured(provider)) {
                row.put("status", "NON CONFIGURÉ");
                row.put("detail", "Clé / URL absente pour ce moteur.");
            } else {
                long start = System.currentTimeMillis();
                try {
                    List<WebSearchResultItem> results = callProvider(provider, "Ecobank");
                    unreachableUntil.remove(provider);
                    row.put("status", results.isEmpty() ? "VIDE" : "OK");
                    row.put("detail", results.size() + " résultat(s) en " + (System.currentTimeMillis() - start) + " ms.");
                } catch (Exception e) {
                    row.put("status", "ÉCHEC");
                    row.put("detail", e.getClass().getSimpleName() + " : " + e.getMessage());
                }
            }
            report.add(row);
        }
        return report;
    }

    // ══════════════════════════════════════════════════════════════════════

    private List<String> orderedProviders() {
        List<String> ids = new ArrayList<>();
        String order = properties.getProviderOrder();
        if (order != null) {
            for (String id : order.split(",")) {
                String p = id.trim().toLowerCase(Locale.ROOT);
                if (!p.isEmpty() && !ids.contains(p)) ids.add(p);
            }
        }
        // Compatibilité : une ancienne configuration Bing avec clé reste utilisée même si
        // "bing" n'a pas été ajouté à provider-order.
        if (!ids.contains("bing") && notBlank(properties.getBing().getKey())) ids.add("bing");
        return ids;
    }

    private boolean isProviderConfigured(String provider) {
        return switch (provider) {
            case "searxng" -> notBlank(properties.getSearxng().getUrl());
            case "brave" -> notBlank(properties.getBrave().getKey());
            case "tavily" -> notBlank(properties.getTavily().getKey());
            case "google" -> notBlank(properties.getGoogle().getKey()) && notBlank(properties.getGoogle().getCx());
            case "bing" -> notBlank(properties.getBing().getKey());
            case "wikipedia" -> properties.getWikipedia().isEnabled();
            case "ecobank", "duckduckgo" -> properties.isFreeWebEnabled();
            default -> false;
        };
    }

    private List<WebSearchResultItem> callProvider(String provider, String query) throws Exception {
        return switch (provider) {
            case "searxng" -> searxng(query);
            case "brave" -> brave(query);
            case "tavily" -> tavily(query);
            case "google" -> google(query);
            case "bing" -> bing(query);
            case "wikipedia" -> wikipedia(query);
            case "ecobank" -> duckDuckGo("site:ecobank.com " + query);
            case "duckduckgo" -> duckDuckGo(query);
            default -> List.of();
        };
    }

    private List<WebSearchResultItem> searxng(String query) throws Exception {
        String base = properties.getSearxng().getUrl().replaceAll("/+$", "");
        String url = base + "/search?format=json&safesearch=2&q=" + enc(query) + "&language=" + enc(lang());
        JsonNode root = getJson(HttpRequest.newBuilder(URI.create(url)));
        return collect(root.path("results"), "title", "content", "url");
    }

    private List<WebSearchResultItem> brave(String query) throws Exception {
        String url = properties.getBrave().getEndpoint() + "?q=" + enc(query) + "&count=" + count()
                + "&search_lang=" + enc(lang()) + "&safesearch=strict";
        JsonNode root = getJson(HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", properties.getBrave().getKey().trim()));
        return collect(root.path("web").path("results"), "title", "description", "url");
    }

    private List<WebSearchResultItem> tavily(String query) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "query", query, "max_results", count(), "search_depth", "basic"));
        JsonNode root = getJson(HttpRequest.newBuilder(URI.create(properties.getTavily().getEndpoint()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getTavily().getKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
        return collect(root.path("results"), "title", "content", "url");
    }

    private List<WebSearchResultItem> google(String query) throws Exception {
        WebSearchProperties.Google g = properties.getGoogle();
        String url = g.getEndpoint() + "?key=" + enc(g.getKey().trim()) + "&cx=" + enc(g.getCx().trim())
                + "&q=" + enc(query) + "&num=" + Math.min(10, count()) + "&safe=active&hl=" + enc(lang());
        JsonNode root = getJson(HttpRequest.newBuilder(URI.create(url)));
        return collect(root.path("items"), "title", "snippet", "link");
    }

    private List<WebSearchResultItem> bing(String query) throws Exception {
        WebSearchProperties.Bing bing = properties.getBing();
        String url = bing.getEndpoint() + "?q=" + enc(query) + "&mkt=" + enc(bing.getMarket())
                + "&count=" + count() + "&safeSearch=Strict";
        JsonNode root = getJson(HttpRequest.newBuilder(URI.create(url))
                .header("Ocp-Apim-Subscription-Key", bing.getKey().trim()));
        return collect(root.path("webPages").path("value"), "name", "snippet", "url");
    }

    private List<WebSearchResultItem> wikipedia(String query) throws Exception {
        String host = "https://" + lang() + ".wikipedia.org";
        String url = host + "/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=" + count()
                + "&srsearch=" + enc(query);
        JsonNode root = getJson(HttpRequest.newBuilder(URI.create(url)));
        List<WebSearchResultItem> results = new ArrayList<>();
        for (JsonNode item : root.path("query").path("search")) {
            String title = item.path("title").asText("");
            if (title.isEmpty()) continue;
            results.add(new WebSearchResultItem(
                    title + " — Wikipédia",
                    cleanSnippet(item.path("snippet").asText("")),
                    host + "/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "_")));
        }
        return results;
    }

    private static final java.util.regex.Pattern DDG_RESULT = java.util.regex.Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>(.*?)(?=<a[^>]*class=\"result__a\"|$)",
            java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern DDG_SNIPPET = java.util.regex.Pattern.compile(
            "class=\"result__snippet\"[^>]*>(.*?)</(?:a|div|td)>", java.util.regex.Pattern.DOTALL);

    /**
     * DuckDuckGo (version HTML légère) — GRATUIT et SANS CLÉ : de vrais résultats web, pas
     * seulement Wikipédia. Utilisé aussi avec « site:ecobank.com » pour privilégier les pages
     * officielles d'Ecobank. Page de contrôle anti-robot ⇒ liste vide (moteur suivant).
     */
    List<WebSearchResultItem> duckDuckGo(String query) throws Exception {
        String region = "fr".equals(lang()) ? "fr-fr" : "wt-wt";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://html.duckduckgo.com/html/"))
                .timeout(Duration.ofSeconds(Math.max(3, properties.getTimeoutSeconds())))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) RCC-Portal/1.0")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept-Language", lang())
                .POST(HttpRequest.BodyPublishers.ofString("q=" + enc(query) + "&kl=" + region, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return parseDuckDuckGo(response.body(), count());
    }

    static List<WebSearchResultItem> parseDuckDuckGo(String html, int max) {
        List<WebSearchResultItem> results = new ArrayList<>();
        if (html == null) return results;
        java.util.regex.Matcher m = DDG_RESULT.matcher(html);
        while (m.find() && results.size() < max) {
            String href = m.group(1).replace("&amp;", "&");
            int u = href.indexOf("uddg=");
            if (u >= 0) {
                String encoded = href.substring(u + 5);
                int amp = encoded.indexOf('&');
                href = java.net.URLDecoder.decode(amp >= 0 ? encoded.substring(0, amp) : encoded, StandardCharsets.UTF_8);
            }
            if (href.startsWith("//")) href = "https:" + href;
            // Publicités DuckDuckGo et liens non web écartés.
            if (!(href.startsWith("https://") || href.startsWith("http://")) || href.contains("duckduckgo.com/y.js")) continue;
            java.util.regex.Matcher sm = DDG_SNIPPET.matcher(m.group(3));
            String snippet = sm.find() ? cleanSnippet(sm.group(1)) : "";
            String title = cleanSnippet(m.group(2));
            if (title.isEmpty()) continue;
            results.add(new WebSearchResultItem(title, snippet, href));
        }
        return results;
    }

    /**
     * Recherche « large » pour RAF : d'abord les pages OFFICIELLES Ecobank, puis le web général,
     * sans doublon — RAF n'est plus limité aux contenus du portail.
     */
    public List<WebSearchResultItem> searchWide(String query) {
        if (!isConfigured() || query == null || query.isBlank()) return List.of();
        String cacheKey = "wide|" + query.trim().toLowerCase(Locale.ROOT);
        synchronized (cache) {
            CacheEntry entry = cache.get(cacheKey);
            if (entry != null && entry.expiresAt() > System.currentTimeMillis()) return entry.results();
        }
        List<WebSearchResultItem> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (isProviderConfigured("ecobank") && !skipped("ecobank")) {
            try {
                for (WebSearchResultItem r : callProvider("ecobank", query.trim())) {
                    if (merged.size() >= 2) break;
                    if (seen.add(r.url())) merged.add(r);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (Exception e) {
                markFailure("ecobank", e);
                log.warn("Recherche web : ecobank.com a échoué pour « {} » : {}", query, e.getMessage());
            }
        }
        for (WebSearchResultItem r : search(query)) {
            if (merged.size() >= count()) break;
            if (seen.add(r.url())) merged.add(r);
        }
        if (!merged.isEmpty() && properties.getCacheMinutes() > 0) {
            synchronized (cache) {
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis() + properties.getCacheMinutes() * 60_000L, merged));
            }
        }
        return merged;
    }

    private JsonNode getJson(HttpRequest.Builder builder) throws Exception {
        HttpRequest request = builder.timeout(Duration.ofSeconds(Math.max(3, properties.getTimeoutSeconds())))
                .header("User-Agent", USER_AGENT)
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            throw new IllegalStateException("HTTP " + response.statusCode()
                    + (body.isBlank() ? "" : " — " + (body.length() > 200 ? body.substring(0, 200) + "…" : body)));
        }
        return objectMapper.readTree(response.body());
    }

    private List<WebSearchResultItem> collect(JsonNode array, String titleField, String snippetField, String urlField) {
        List<WebSearchResultItem> results = new ArrayList<>();
        if (!array.isArray()) return results;
        for (JsonNode node : array) {
            String url = node.path(urlField).asText("");
            // Seuls les liens web classiques — jamais de javascript:/data: injectés dans un href.
            if (!(url.startsWith("https://") || url.startsWith("http://"))) continue;
            results.add(new WebSearchResultItem(
                    cleanSnippet(node.path(titleField).asText("")),
                    cleanSnippet(node.path(snippetField).asText("")),
                    url));
            if (results.size() >= count()) break;
        }
        return results;
    }

    /** Retire le balisage de surlignage (&lt;strong&gt;, &lt;span class="searchmatch"&gt;) et les entités courantes. */
    private static String cleanSnippet(String s) {
        if (s == null) return "";
        String text = s.replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private int count() {
        return Math.max(1, Math.min(10, properties.getResultCount()));
    }

    private String lang() {
        String l = properties.getLanguage();
        String code = l == null ? "" : l.trim().toLowerCase(Locale.ROOT);
        return code.matches("[a-z]{2,3}") ? code : "fr"; // sert aussi de sous-domaine Wikipédia : validé strictement
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

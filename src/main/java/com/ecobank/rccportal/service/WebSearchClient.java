package com.ecobank.rccportal.service;

import com.ecobank.rccportal.config.WebSearchProperties;
import com.ecobank.rccportal.dto.WebSearchResultItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Client de recherche web (Bing Web Search API) — utilisé par RAF UNIQUEMENT
 * en repli, quand la Base de connaissances et les procédures internes
 * n'apportent pas de réponse suffisante à la question posée. Ne remplace
 * jamais la connaissance métier Ecobank : les résultats web sont toujours
 * présentés comme une source externe, distincte des procédures internes.
 *
 * Comme AnthropicClient / AzureOpenAiClient / CopilotStudioClient : jamais
 * de blocage silencieux — si la clé n'est pas configurée ou si l'appel
 * échoue, on le signale clairement dans les logs et on continue sans
 * résultat web (RAF répond quand même avec ce qu'il a en interne) plutôt
 * que de faire échouer toute la requête.
 */
@Service
public class WebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(WebSearchClient.class);

    private final WebSearchProperties properties;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSearchClient(WebSearchProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getBing().getKey() != null
                && !properties.getBing().getKey().isBlank();
    }

    /**
     * Recherche sur le web. Retourne une liste vide (jamais d'exception) en
     * cas d'échec — le repli web est une amélioration best-effort, pas un
     * point de blocage pour RAF.
     */
    public List<WebSearchResultItem> search(String query) {
        if (!isConfigured()) {
            log.debug("Recherche web : non configurée (websearch.enabled/websearch.bing.key), ignorée.");
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            WebSearchProperties.Bing bing = properties.getBing();
            String url = bing.getEndpoint()
                    + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&mkt=" + URLEncoder.encode(bing.getMarket(), StandardCharsets.UTF_8)
                    + "&count=" + bing.getResultCount()
                    + "&safeSearch=Strict";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(bing.getTimeoutSeconds()))
                    .header("Ocp-Apim-Subscription-Key", bing.getKey())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Recherche web : Bing a répondu HTTP {} : {}", response.statusCode(), response.body());
                return List.of();
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode values = json.path("webPages").path("value");
            List<WebSearchResultItem> results = new ArrayList<>();
            if (values.isArray()) {
                for (JsonNode value : values) {
                    results.add(new WebSearchResultItem(
                            value.path("name").asText(""),
                            value.path("snippet").asText(""),
                            value.path("url").asText("")
                    ));
                }
            }
            log.debug("Recherche web : {} résultat(s) pour « {} ».", results.size(), query);
            return results;

        } catch (IOException e) {
            log.warn("Recherche web : erreur réseau lors de la requête « {} » : {}", query, e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Recherche web : requête interrompue pour « {} ».", query);
            return List.of();
        } catch (Exception e) {
            log.warn("Recherche web : échec inattendu pour « {} » : {}", query, e.getMessage());
            return List.of();
        }
    }
}

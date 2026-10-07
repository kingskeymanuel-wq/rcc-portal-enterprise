package com.ecobank.rccportal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client Anthropic (API Messages) — utilisé par RAF pour ses réponses conversationnelles.
 * Même principe que AzureOpenAiClient : configuration externe, jamais de blocage silencieux —
 * une erreur claire si la clé n'est pas renseignée, jamais une réponse inventée à la place.
 */
@Service
public class AnthropicClient {

    @Value("${quality.ai.anthropic-key:}")
    private String apiKey;

    @Value("${quality.ai.anthropic-model:claude-sonnet-4-5}")
    private String model;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnthropicClient.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** IA installée sur le réseau interne (Ollama) : utilisée hors ligne ou sans clé Anthropic. */
    private LocalAiClient local;
    private OfflineMode offlineMode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLocal(LocalAiClient local) {
        this.local = local;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOfflineMode(OfflineMode offlineMode) {
        this.offlineMode = offlineMode;
    }

    private boolean offline() {
        return offlineMode != null && offlineMode.isEnabled();
    }

    private boolean cloudKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** IA locale retenue : mode hors ligne, ou pas de clé Anthropic. */
    public boolean usesLocal() {
        return local != null && local.isConfigured() && (offline() || !cloudKey());
    }

    /** Une IA est disponible : locale, ou Anthropic (jamais Anthropic en mode hors ligne). */
    public boolean isConfigured() {
        return usesLocal() || (cloudKey() && !offline());
    }

    /**
     * Coupe-circuit : après un refus durable (crédit épuisé, clé invalide) ou une panne, les appels
     * suivants échouent tout de suite au lieu de refaire attendre chaque agent — le portail bascule
     * aussitôt sur ses moteurs locaux (réécriture, synthèse RAF). Nouvel essai automatique ensuite.
     */
    private volatile long pausedUntil;
    private volatile String pauseReason;

    /** Clé renseignée ET pas de refus récent : l'IA peut être appelée sans risque d'attente inutile. */
    public boolean isAvailable() {
        return usesLocal() || (isConfigured() && System.currentTimeMillis() >= pausedUntil);
    }

    /** Raison courte de l'indisponibilité (pour l'affichage), {@code null} si l'IA est disponible. */
    public String unavailableReason() {
        if (usesLocal()) return null;
        if (offline()) return "IA locale non configurée (mode hors ligne)";
        if (!isConfigured()) return "IA non configurée";
        return System.currentTimeMillis() < pausedUntil ? pauseReason : null;
    }

    private void checkAvailable() {
        if (!isConfigured()) {
            throw ApiException.serviceUnavailable(
                    "Anthropic n'est pas configuré (quality.ai.anthropic-key). " +
                    "Renseignez ANTHROPIC_API_KEY (clé disponible sur console.anthropic.com).");
        }
        String reason = unavailableReason();
        if (reason != null) throw ApiException.serviceUnavailable(reason);
    }

    private ApiException pause(String reason, long millis) {
        pauseReason = reason;
        pausedUntil = System.currentTimeMillis() + millis;
        return ApiException.serviceUnavailable(reason);
    }

    /** Réponse HTTP en erreur → message court et lisible (le détail brut part dans les journaux). */
    ApiException failure(int status, String body) {
        String b = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        log.warn("Anthropic HTTP {} : {}", status, body == null ? "" : body.length() > 500 ? body.substring(0, 500) : body);
        if (b.contains("credit balance") || b.contains("billing")) {
            return pause("crédit Anthropic épuisé — à recharger par l'administrateur", 15 * 60_000L);
        }
        if (status == 401 || status == 403) return pause("clé Anthropic invalide ou non autorisée", 15 * 60_000L);
        if (status == 429) return pause("IA momentanément saturée, nouvel essai dans une minute", 60_000L);
        if (status >= 500) return pause("service IA momentanément indisponible", 60_000L);
        return ApiException.serviceUnavailable("demande refusée par l'IA (HTTP " + status + ")");
    }

    public String chat(String systemPrompt, String userPrompt, int maxTokens) {
        return chat(systemPrompt, userPrompt, maxTokens, 25);
    }

    /** timeoutSeconds : 25s convient à la quasi-totalité des usages (synthèse courte, page qui
     *  attend la réponse) — seule l'analyse de fichier volumineux (RalphSearchService.analyzeFile)
     *  a besoin de plus. Avant ce changement, un timeout unique de 2 minutes faisait attendre
     *  l'utilisateur bien trop longtemps en cas de lenteur/indisponibilité d'Anthropic. */
    public String chat(String systemPrompt, String userPrompt, int maxTokens, int timeoutSeconds) {
        if (usesLocal()) return local.chat(systemPrompt, userPrompt, maxTokens);
        checkAvailable();
        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", userPrompt))
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw failure(response.statusCode(), response.body());

            return textOf(objectMapper.readTree(response.body()));

        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            log.warn("Anthropic injoignable : {}", e.toString());
            throw pause(e instanceof java.net.http.HttpTimeoutException ? "IA trop lente à répondre" : "IA injoignable depuis le serveur", 60_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Appel à Anthropic interrompu.");
        }
    }

    /**
     * Variante vision — envoie une image (capture d'écran, photo de tableau) en base64 avec
     * un prompt texte. Utilisé pour l'import KPI par capture d'écran (voir
     * ManualKpiEntryService.importFromScreenshot()) — même contrat d'erreur que chat().
     */
    public String chatWithImage(String systemPrompt, String userPrompt, String imageBase64, String mediaType, int maxTokens) {
        if (local != null && local.isVisionConfigured() && (offline() || !cloudKey())) {
            return local.chatWithImage(systemPrompt, userPrompt, imageBase64, mediaType, maxTokens);
        }
        if (offline()) throw ApiException.serviceUnavailable("Lecture d'image : modèle de vision local non configuré (mode hors ligne).");
        checkAvailable();
        try {
            List<Map<String, Object>> content = List.of(
                    Map.of("type", "image", "source", Map.of(
                            "type", "base64", "media_type", mediaType, "data", imageBase64)),
                    Map.of("type", "text", "text", userPrompt)
            );
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", content))
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(60)) // une image demande plus de temps qu'un simple texte
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw failure(response.statusCode(), response.body());

            return textOf(objectMapper.readTree(response.body()));

        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            log.warn("Anthropic injoignable : {}", e.toString());
            throw pause(e instanceof java.net.http.HttpTimeoutException ? "IA trop lente à répondre" : "IA injoignable depuis le serveur", 60_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Appel à Anthropic interrompu.");
        }
    }

    /**
     * Texte de la réponse : tous les blocs « text », dans l'ordre. Les modèles récents peuvent renvoyer
     * d'abord un bloc « thinking » : lire seulement le premier bloc donnait une réponse vide.
     */
    static String textOf(JsonNode json) {
        if ("refusal".equals(json.path("stop_reason").asText())) {
            throw ApiException.serviceUnavailable("Anthropic a décliné cette demande.");
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode block : json.path("content")) {
            if ("text".equals(block.path("type").asText("text"))) out.append(block.path("text").asText(""));
        }
        if (out.length() == 0) throw ApiException.serviceUnavailable("Réponse inattendue d'Anthropic (aucun texte).");
        return out.toString();
    }
}

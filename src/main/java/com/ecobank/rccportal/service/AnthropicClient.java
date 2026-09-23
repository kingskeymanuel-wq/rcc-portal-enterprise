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

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String chat(String systemPrompt, String userPrompt, int maxTokens) {
        return chat(systemPrompt, userPrompt, maxTokens, 25);
    }

    /** timeoutSeconds : 25s convient à la quasi-totalité des usages (synthèse courte, page qui
     *  attend la réponse) — seule l'analyse de fichier volumineux (RalphSearchService.analyzeFile)
     *  a besoin de plus. Avant ce changement, un timeout unique de 2 minutes faisait attendre
     *  l'utilisateur bien trop longtemps en cas de lenteur/indisponibilité d'Anthropic. */
    public String chat(String systemPrompt, String userPrompt, int maxTokens, int timeoutSeconds) {
        if (!isConfigured()) {
            throw ApiException.serviceUnavailable(
                    "Anthropic n'est pas configuré (quality.ai.anthropic-key). " +
                    "Renseignez ANTHROPIC_API_KEY (clé disponible sur console.anthropic.com).");
        }
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
            if (response.statusCode() != 200) {
                throw ApiException.serviceUnavailable(
                        "Anthropic a répondu HTTP " + response.statusCode() + " : " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode content = json.path("content");
            if (content.isArray() && !content.isEmpty()) {
                return content.get(0).path("text").asText("");
            }
            throw ApiException.serviceUnavailable("Réponse inattendue d'Anthropic.");

        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de joindre Anthropic : " + e.getMessage());
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
        if (!isConfigured()) {
            throw ApiException.serviceUnavailable(
                    "Anthropic n'est pas configuré (quality.ai.anthropic-key). " +
                    "Renseignez ANTHROPIC_API_KEY (clé disponible sur console.anthropic.com).");
        }
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
            if (response.statusCode() != 200) {
                throw ApiException.serviceUnavailable(
                        "Anthropic a répondu HTTP " + response.statusCode() + " : " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode responseContent = json.path("content");
            if (responseContent.isArray() && !responseContent.isEmpty()) {
                return responseContent.get(0).path("text").asText("");
            }
            throw ApiException.serviceUnavailable("Réponse inattendue d'Anthropic.");

        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de joindre Anthropic : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Appel à Anthropic interrompu.");
        }
    }
}

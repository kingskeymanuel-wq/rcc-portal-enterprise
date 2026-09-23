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
 * Client Azure OpenAI partagé — même ressource et mêmes identifiants que
 * QualityAiService (analyse des écoutes), réutilisés ici pour RAF et
 * l'analyse de données IT. Centralise l'appel HTTP pour éviter de le
 * dupliquer à chaque nouvel usage de l'IA sur le portail.
 */
@Service
public class AzureOpenAiClient {

    @Value("${quality.ai.azure-openai-endpoint:}")
    private String endpoint;

    @Value("${quality.ai.azure-openai-key:}")
    private String apiKey;

    @Value("${quality.ai.azure-openai-deployment:gpt-4o}")
    private String deployment;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    /** Lève une ApiException claire si l'IA n'est pas configurée — jamais de réponse inventée en silence. */
    public String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        if (!isConfigured()) {
            throw ApiException.serviceUnavailable(
                    "Azure OpenAI Service n'est pas configuré (quality.ai.azure-openai-endpoint / -key). " +
                    "Contactez l'équipe infrastructure pour déployer la ressource et renseigner les identifiants.");
        }
        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    ),
                    "temperature", temperature,
                    "max_tokens", maxTokens
            ));

            String url = endpoint.replaceAll("/$", "") +
                    "/openai/deployments/" + deployment +
                    "/chat/completions?api-version=2024-08-01-preview";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw ApiException.serviceUnavailable(
                        "Azure OpenAI a répondu HTTP " + response.statusCode() + " : " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode choices = json.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText("");
            }
            throw ApiException.serviceUnavailable("Réponse inattendue d'Azure OpenAI.");

        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de joindre Azure OpenAI : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Appel à Azure OpenAI interrompu.");
        }
    }
}

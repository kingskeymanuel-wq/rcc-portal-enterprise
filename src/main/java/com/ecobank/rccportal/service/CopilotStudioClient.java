package com.ecobank.rccportal.service;

import com.ecobank.rccportal.config.CopilotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ecobank.rccportal.util.ApiException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Client Microsoft Copilot Studio via le protocole Direct Line 3.0 — utilisé par RAF pour
 * ses réponses conversationnelles. L'agent Copilot Studio gère lui-même l'orchestration,
 * le RAG (SharePoint, documents métier) et la génération de réponse ; ce client ne fait que
 * relayer la question et récupérer la réponse, sans grounding local.
 *
 * Protocole Direct Line 3.0 :
 *   1. POST /v3/directline/conversations               -> ouvre une conversation, retourne un token
 *   2. POST /v3/directline/conversations/{id}/activities -> envoie le message utilisateur
 *   3. GET  /v3/directline/conversations/{id}/activities -> poll jusqu'à la réponse du bot
 *
 * Configuration externe uniquement (CopilotProperties, jamais de secret en dur) — jamais de
 * blocage silencieux : une erreur claire si le secret n'est pas renseigné, jamais une réponse
 * inventée à la place. "copilot.enabled" est un interrupteur explicite indépendant du secret.
 */
@Service
public class CopilotStudioClient {

    private static final Logger log = LoggerFactory.getLogger(CopilotStudioClient.class);

    private final CopilotProperties copilotProperties;

    /** Intervalle entre deux polls d'activités en attendant la réponse du bot. */
    private static final long POLL_INTERVAL_MS = 800;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CopilotStudioClient(CopilotProperties copilotProperties) {
        this.copilotProperties = copilotProperties;
    }

    @PostConstruct
    public void logConfigurationStatus() {
        boolean secretPresent = copilotProperties.getDirectline().getSecret() != null
                && !copilotProperties.getDirectline().getSecret().isBlank();
        log.info("Copilot Studio — enabled: {} | secret présent: {} | endpoint: {}",
                copilotProperties.isEnabled(), secretPresent, copilotProperties.getDirectline().getEndpoint());
    }

    public boolean isConfigured() {
        String secret = copilotProperties.getDirectline().getSecret();
        return copilotProperties.isEnabled() && secret != null && !secret.isBlank();
    }

    /**
     * Point d'entrée unique : ouvre une conversation, envoie la question, attend et retourne
     * la première réponse texte du bot. Une conversation Direct Line par appel — l'agent
     * Copilot Studio ne conserve donc pas de mémoire entre deux questions RAF successives.
     */
    public String ask(String question) {
        if (!isConfigured()) {
            throw ApiException.serviceUnavailable(
                    "Copilot Studio n'est pas configuré (copilot.directline.secret). " +
                    "Renseignez COPILOT_DIRECTLINE_SECRET (Copilot Studio > Paramètres > Canaux > " +
                    "Application web ou mobile personnalisée).");
        }

        String conversationId = startConversation();
        String fromId = "rcc-portal-user-" + UUID.randomUUID();
        sendMessage(conversationId, question, fromId);
        return getLatestResponse(conversationId, fromId);
    }

    /**
     * Ouvre une nouvelle conversation Direct Line.
     * @return l'identifiant de conversation à réutiliser pour les appels suivants
     */
    public String startConversation() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(copilotProperties.getDirectline().getEndpoint().replaceAll("/$", "") + "/v3/directline/conversations"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + copilotProperties.getDirectline().getSecret())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        JsonNode json = send(request, "ouverture de conversation");
        String conversationId = json.path("conversationId").asText(null);
        if (conversationId == null || conversationId.isBlank()) {
            throw ApiException.serviceUnavailable("Copilot Studio n'a pas retourné de conversationId.");
        }
        log.debug("Copilot Studio : conversation ouverte {}", conversationId);
        return conversationId;
    }

    /**
     * Envoie le message utilisateur dans la conversation donnée.
     * @param fromId identifiant de l'expéditeur, utilisé ensuite pour distinguer la réponse du bot
     */
    public void sendMessage(String conversationId, String message, String fromId) {
        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "type", "message",
                    "from", Map.of("id", fromId),
                    "text", message
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(copilotProperties.getDirectline().getEndpoint().replaceAll("/$", "") + "/v3/directline/conversations/"
                            + conversationId + "/activities"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + copilotProperties.getDirectline().getSecret())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            send(request, "envoi du message");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.serviceUnavailable("Impossible de construire la requête Copilot Studio : " + e.getMessage());
        }
    }

    /**
     * Overload pratique — génère un fromId à usage unique. Préférer la version avec fromId
     * explicite si l'appelant a déjà ouvert la conversation via {@link #startConversation()}.
     */
    public void sendMessage(String conversationId, String message) {
        sendMessage(conversationId, message, "rcc-portal-user-" + UUID.randomUUID());
    }

    /**
     * Poll les activités de la conversation jusqu'à recevoir un message dont l'expéditeur
     * n'est pas l'utilisateur (fromId) — c'est-à-dire la réponse de l'agent Copilot Studio.
     * S'arrête en erreur au bout de {@code timeout-seconds} sans réponse.
     */
    public String getLatestResponse(String conversationId, String fromId) {
        Instant deadline = Instant.now().plusSeconds(copilotProperties.getDirectline().getTimeoutSeconds());
        String watermark = null;

        while (Instant.now().isBefore(deadline)) {
            String url = copilotProperties.getDirectline().getEndpoint().replaceAll("/$", "") + "/v3/directline/conversations/"
                    + conversationId + "/activities" + (watermark != null ? "?watermark=" + watermark : "");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + copilotProperties.getDirectline().getSecret())
                    .GET()
                    .build();

            JsonNode json = send(request, "récupération des réponses");
            watermark = json.path("watermark").asText(watermark);

            JsonNode activities = json.path("activities");
            if (activities.isArray()) {
                for (JsonNode activity : activities) {
                    boolean isMessage = "message".equals(activity.path("type").asText());
                    String activityFromId = activity.path("from").path("id").asText("");
                    boolean isFromBot = !activityFromId.equals(fromId);
                    String text = activity.path("text").asText("");

                    if (isMessage && isFromBot && !text.isBlank()) {
                        log.debug("Copilot Studio : réponse reçue pour la conversation {}", conversationId);
                        return text;
                    }
                }
            }

            sleep();
        }

        log.warn("Copilot Studio : timeout ({}s) sans réponse pour la conversation {}", copilotProperties.getDirectline().getTimeoutSeconds(), conversationId);
        throw ApiException.serviceUnavailable(
                "Copilot Studio n'a pas répondu dans le délai imparti (" + copilotProperties.getDirectline().getTimeoutSeconds() + "s).");
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Attente de la réponse Copilot Studio interrompue.");
        }
    }

    private JsonNode send(HttpRequest request, String stepDescription) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Copilot Studio : échec HTTP {} lors de {} : {}",
                        response.statusCode(), stepDescription, response.body());
                throw ApiException.serviceUnavailable(
                        "Copilot Studio a répondu HTTP " + response.statusCode()
                                + " lors de " + stepDescription + " : " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            log.warn("Copilot Studio : erreur réseau lors de {} : {}", stepDescription, e.getMessage());
            throw ApiException.serviceUnavailable("Impossible de joindre Copilot Studio (" + stepDescription + ") : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Appel à Copilot Studio interrompu (" + stepDescription + ").");
        }
    }
}

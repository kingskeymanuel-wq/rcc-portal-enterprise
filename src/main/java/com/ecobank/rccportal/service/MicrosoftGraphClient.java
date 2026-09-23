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
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Client Microsoft Graph — intégration réelle Teams/Outlook, authentification par
 * identifiants d'application (client credentials, permissions "Application" — pas de
 * connexion utilisateur). Voir docs/GRAPH_SETUP.md pour la procédure d'inscription
 * dans Azure AD / Entra ID (obligatoire côté client, je ne peux pas la faire à sa place).
 *
 * LIMITE IMPORTANTE (Teams) : Microsoft restreint fortement l'envoi de messages Teams
 * par permissions "Application" seules (ChatMessage.Send app-only est soumis à une
 * validation Microsoft spécifique, pas garanti pour un tenant standard). sendTeamsMessage()
 * tente l'appel réel ; en cas de refus (403/permission manquante), l'appelant doit
 * retomber sur le lien Teams classique (deep link web) — c'est ce que fait le contrôleur.
 * L'envoi Outlook (Mail.Send), lui, fonctionne de façon fiable en permissions Application.
 */
@Service
public class MicrosoftGraphClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MicrosoftGraphClient.class);

    @Value("${graph.tenant-id:}")
    private String tenantId;
    @Value("${graph.client-id:}")
    private String clientId;
    @Value("${graph.client-secret:}")
    private String clientSecret;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public boolean isConfigured() {
        return tenantId != null && !tenantId.isBlank()
                && clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    /** Jeton d'application (client credentials) — mis en cache jusqu'à expiration (marge de 60s). */
    private synchronized String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }
        if (!isConfigured()) {
            throw ApiException.serviceUnavailable(
                    "Microsoft Graph n'est pas configuré (graph.tenant-id / client-id / client-secret). " +
                    "Voir docs/GRAPH_SETUP.md pour l'inscription Azure AD.");
        }
        try {
            String body = "client_id=" + clientId +
                    "&scope=" + java.net.URLEncoder.encode("https://graph.microsoft.com/.default", StandardCharsets.UTF_8) +
                    "&client_secret=" + java.net.URLEncoder.encode(clientSecret, StandardCharsets.UTF_8) +
                    "&grant_type=client_credentials";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw ApiException.serviceUnavailable("Azure AD a refusé l'authentification (HTTP " + response.statusCode() + ") : " + response.body());
            }
            JsonNode json = objectMapper.readTree(response.body());
            cachedToken = json.path("access_token").asText();
            int expiresIn = json.path("expires_in").asInt(3600);
            tokenExpiry = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
            return cachedToken;
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de joindre Azure AD : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Authentification Azure AD interrompue.");
        }
    }

    /**
     * Envoie un e-mail réel via Microsoft Graph (Outlook), en permissions Application
     * (Mail.Send) — l'e-mail part réellement de la boîte {@code fromUserEmail} (doit être
     * une adresse valide du tenant Ecobank), et non d'une adresse générique.
     */
    public void sendMail(String fromUserEmail, String toEmail, String subject, String bodyText) {
        String token = accessToken();
        try {
            Map<String, Object> payload = Map.of(
                    "message", Map.of(
                            "subject", subject,
                            "body", Map.of("contentType", "Text", "content", bodyText),
                            "toRecipients", List.of(Map.of("emailAddress", Map.of("address", toEmail)))
                    ),
                    "saveToSentItems", true
            );
            String requestJson = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.microsoft.com/v1.0/users/" + fromUserEmail + "/sendMail"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 202 && response.statusCode() != 200) {
                throw ApiException.serviceUnavailable("Microsoft Graph (sendMail) a répondu HTTP " + response.statusCode() + " : " + response.body());
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de joindre Microsoft Graph : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Envoi Graph interrompu.");
        }
    }

    /**
     * Tente d'envoyer un vrai message Teams (crée le chat 1:1 s'il n'existe pas encore, puis
     * poste le message) — voir avertissement en tête de classe : peut échouer (403) selon les
     * restrictions Microsoft sur ce tenant, même avec la permission accordée. L'appelant doit
     * prévoir un repli (lien Teams classique) en cas d'échec.
     */
    public void sendTeamsMessage(String fromUserId, String toUserId, String message) {
        String token = accessToken();
        try {
            // 1) Créer (ou retrouver) le chat 1:1 entre les deux utilisateurs.
            Map<String, Object> chatPayload = Map.of(
                    "chatType", "oneOnOne",
                    "members", List.of(
                            Map.of("@odata.type", "#microsoft.graph.aadUserConversationMember",
                                    "roles", List.of("owner"), "user@odata.bind", "https://graph.microsoft.com/v1.0/users('" + fromUserId + "')"),
                            Map.of("@odata.type", "#microsoft.graph.aadUserConversationMember",
                                    "roles", List.of("owner"), "user@odata.bind", "https://graph.microsoft.com/v1.0/users('" + toUserId + "')")
                    )
            );
            String chatRequestJson = objectMapper.writeValueAsString(chatPayload);

            HttpRequest chatRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.microsoft.com/v1.0/chats"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(chatRequestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> chatResponse = httpClient.send(chatRequest, HttpResponse.BodyHandlers.ofString());
            if (chatResponse.statusCode() != 201 && chatResponse.statusCode() != 200) {
                throw ApiException.serviceUnavailable(
                        "Microsoft Graph a refusé la création du chat Teams (HTTP " + chatResponse.statusCode() + ") — " +
                        "permissions probablement insuffisantes pour ce tenant : " + chatResponse.body());
            }
            JsonNode chatJson = objectMapper.readTree(chatResponse.body());
            String chatId = chatJson.path("id").asText();

            // 2) Poster le message dans ce chat.
            String messageRequestJson = objectMapper.writeValueAsString(
                    Map.of("body", Map.of("content", message)));

            HttpRequest messageRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.microsoft.com/v1.0/chats/" + chatId + "/messages"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(messageRequestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> messageResponse = httpClient.send(messageRequest, HttpResponse.BodyHandlers.ofString());
            if (messageResponse.statusCode() != 201 && messageResponse.statusCode() != 200) {
                throw ApiException.serviceUnavailable("Microsoft Graph a refusé l'envoi du message Teams (HTTP " + messageResponse.statusCode() + ").");
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de joindre Microsoft Graph : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Envoi Graph interrompu.");
        }
    }
}

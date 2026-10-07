package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IA installée sur le réseau interne — API compatible OpenAI :
 * <ul>
 *   <li>texte et images : Ollama ({@code http://127.0.0.1:11434}, {@code /v1/chat/completions}) ;</li>
 *   <li>transcription d'appels : serveur Whisper local ({@code scripts/offline-kit/whisper_server.py},
 *       {@code /v1/audio/transcriptions}).</li>
 * </ul>
 * Remplace Anthropic, Azure OpenAI et Azure AI Speech quand le portail tourne sans Internet (RCC_OFFLINE=true)
 * ou quand aucune clé cloud n'est renseignée. Aucune donnée ne quitte le réseau Ecobank.
 */
@Service
public class LocalAiClient {

    private static final Logger log = LoggerFactory.getLogger(LocalAiClient.class);

    private final String url;
    private final String model;
    private final String visionModel;
    private final int timeoutSeconds;
    private final String whisperUrl;
    private final String whisperModel;
    private final String whisperLanguage;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper json = new ObjectMapper();

    public LocalAiClient(@Value("${rcc.local-ai.url:}") String url,
                         @Value("${rcc.local-ai.model:qwen2.5:7b}") String model,
                         @Value("${rcc.local-ai.vision-model:}") String visionModel,
                         @Value("${rcc.local-ai.timeout-seconds:180}") int timeoutSeconds,
                         @Value("${rcc.local-whisper.url:}") String whisperUrl,
                         @Value("${rcc.local-whisper.model:small}") String whisperModel,
                         @Value("${rcc.local-whisper.language:fr}") String whisperLanguage) {
        this.url = trim(url);
        this.model = model;
        this.visionModel = trim(visionModel);
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
        this.whisperUrl = trim(whisperUrl);
        this.whisperModel = whisperModel;
        this.whisperLanguage = whisperLanguage;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }

    public boolean isConfigured() { return !url.isEmpty(); }
    public boolean isVisionConfigured() { return isConfigured() && !visionModel.isEmpty(); }
    public boolean isWhisperConfigured() { return !whisperUrl.isEmpty(); }
    public String url() { return url; }
    public String model() { return model; }
    public String visionModel() { return visionModel; }
    public String whisperUrl() { return whisperUrl; }

    /** Réponse texte du modèle local. */
    public String chat(String systemPrompt, String userPrompt, int maxTokens) {
        return complete(model, List.of(
                Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)), maxTokens, 0.3);
    }

    public String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        return complete(model, List.of(
                Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)), maxTokens, temperature);
    }

    /** Lecture d'une image (capture de tableau KPI) par le modèle de vision local (ex. qwen2.5vl, llava). */
    public String chatWithImage(String systemPrompt, String userPrompt, String imageBase64, String mediaType, int maxTokens) {
        if (!isVisionConfigured()) {
            throw ApiException.serviceUnavailable("IA locale : aucun modèle de vision configuré (rcc.local-ai.vision-model).");
        }
        List<Object> content = List.of(
                Map.of("type", "text", "text", userPrompt == null ? "" : userPrompt),
                Map.of("type", "image_url", "image_url", Map.of("url", "data:" + mediaType + ";base64," + imageBase64)));
        return complete(visionModel, List.of(
                Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                Map.of("role", "user", "content", content)), maxTokens, 0.1);
    }

    private String complete(String useModel, List<?> messages, int maxTokens, double temperature) {
        if (!isConfigured()) {
            throw ApiException.serviceUnavailable("IA locale non configurée (RCC_LOCAL_AI_URL).");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", useModel);
            body.put("messages", messages);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);
            body.put("stream", false);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                log.warn("IA locale HTTP {} : {}", res.statusCode(), abbreviate(res.body()));
                throw ApiException.serviceUnavailable("IA locale indisponible (HTTP " + res.statusCode() + ")"
                        + (res.statusCode() == 404 ? " — modèle « " + useModel + " » absent du serveur." : "."));
            }
            JsonNode choices = json.readTree(res.body()).path("choices");
            String text = choices.isArray() && !choices.isEmpty() ? choices.get(0).path("message").path("content").asText("") : "";
            text = text.replaceAll("(?s)<think>.*?</think>", "").trim(); // modèles « raisonneurs » : réflexion retirée
            if (text.isEmpty()) throw ApiException.serviceUnavailable("IA locale : réponse vide.");
            return text;
        } catch (ApiException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw ApiException.serviceUnavailable("IA locale trop lente (plus de " + timeoutSeconds + " s).");
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("IA locale injoignable (" + url + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Appel à l'IA locale interrompu.");
        }
    }

    /** Transcription d'un enregistrement d'appel par le serveur Whisper local (API OpenAI /v1/audio/transcriptions). */
    public String transcribe(Path audio) {
        if (!isWhisperConfigured()) {
            throw ApiException.serviceUnavailable("Transcription locale non configurée (RCC_LOCAL_WHISPER_URL).");
        }
        try {
            String boundary = "----rcc" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            part(out, boundary, "model", whisperModel);
            part(out, boundary, "language", whisperLanguage);
            part(out, boundary, "response_format", "json");
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + audio.getFileName()
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(Files.readAllBytes(audio));
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(whisperUrl + "/v1/audio/transcriptions"))
                    .timeout(Duration.ofMinutes(15))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                log.warn("Whisper local HTTP {} : {}", res.statusCode(), abbreviate(res.body()));
                throw ApiException.serviceUnavailable("Transcription locale indisponible (HTTP " + res.statusCode() + ").");
            }
            String text = json.readTree(res.body()).path("text").asText("").trim();
            if (text.isEmpty()) throw ApiException.serviceUnavailable("Transcription locale : aucun texte reconnu.");
            return text;
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Serveur de transcription local injoignable (" + whisperUrl + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Transcription interrompue.");
        }
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Modèles installés sur le serveur Ollama (diagnostic) — liste vide si injoignable. */
    public List<String> installedModels() {
        List<String> out = new ArrayList<>();
        if (!isConfigured()) return out;
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url + "/v1/models")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 200) json.readTree(res.body()).path("data").forEach(m -> out.add(m.path("id").asText()));
        } catch (IOException e) {
            // injoignable : liste vide
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}

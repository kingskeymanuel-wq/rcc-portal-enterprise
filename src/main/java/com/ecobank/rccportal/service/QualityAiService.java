package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.QualityAnalysisResponse;
import com.ecobank.rccportal.dto.TranscriptionResponse;
import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureStep;
import com.ecobank.rccportal.model.QualityCriterion;
import com.ecobank.rccportal.model.QualityEvaluation;
import com.ecobank.rccportal.repository.ProcedureRepository;
import com.ecobank.rccportal.repository.ProcedureStepRepository;
import com.ecobank.rccportal.repository.QualityCriterionRepository;
import com.ecobank.rccportal.repository.QualityEvaluationRepository;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transcription (Azure AI Speech — Fast Transcription API) et analyse
 * (Azure OpenAI Service) des écoutes. Choix assumé par Ecobank : ces deux
 * services envoient les données (audio + transcription) chez Microsoft —
 * décision prise en connaissance de cause, en remplacement de la première
 * version 100% locale (Vosk + moteur de règles maison).
 *
 * Rien n'est persisté côté RCC Portal : chaque appel recalcule le résultat,
 * transmis directement à l'écran. Tant que les clés Azure ne sont pas
 * configurées, les deux fonctionnalités échouent avec un message clair.
 */
@Service
public class QualityAiService {

    @Value("${quality.audio.storage-dir}")
    private String audioStorageDir;

    @Value("${quality.ai.azure-speech-key:}")
    private String azureSpeechKey;

    @Value("${quality.ai.azure-speech-region:francecentral}")
    private String azureSpeechRegion;

    @Value("${quality.ai.azure-speech-language:fr-FR}")
    private String azureSpeechLanguage;

    @Value("${quality.ai.azure-openai-endpoint:}")
    private String azureOpenAiEndpoint;

    @Value("${quality.ai.azure-openai-key:}")
    private String azureOpenAiKey;

    @Value("${quality.ai.azure-openai-deployment:gpt-4o}")
    private String azureOpenAiDeployment;

    private final QualityEvaluationRepository evaluationRepository;
    private final ProcedureRepository procedureRepository;
    private final ProcedureStepRepository procedureStepRepository;
    private final QualityCriterionRepository criterionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public QualityAiService(
            QualityEvaluationRepository evaluationRepository,
            ProcedureRepository procedureRepository,
            ProcedureStepRepository procedureStepRepository,
            QualityCriterionRepository criterionRepository) {
        this.evaluationRepository = evaluationRepository;
        this.procedureRepository = procedureRepository;
        this.procedureStepRepository = procedureStepRepository;
        this.criterionRepository = criterionRepository;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Transcription — Azure AI Speech, Fast Transcription API
    // ══════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public TranscriptionResponse transcribe(Integer evaluationId) {
        if (azureSpeechKey == null || azureSpeechKey.isBlank()) {
            throw ApiException.serviceUnavailable(
                    "Azure AI Speech n'est pas configuré (quality.ai.azure-speech-key). " +
                    "Contactez l'équipe infrastructure pour créer la ressource Azure et renseigner la clé.");
        }

        QualityEvaluation evaluation = findEvaluation(evaluationId);
        if (evaluation.getRecordingRef() == null || evaluation.getRecordingRef().isBlank()) {
            throw ApiException.badRequest("Cette évaluation n'a pas d'enregistrement audio associé.");
        }

        String filename = evaluation.getRecordingRef().substring(evaluation.getRecordingRef().lastIndexOf('/') + 1);
        Path audioPath = Path.of(audioStorageDir).resolve(filename);
        if (!Files.exists(audioPath)) {
            throw ApiException.notFound("Fichier audio introuvable sur le disque (" + audioPath + ").");
        }

        String transcript = callAzureSpeech(audioPath, filename);
        return new TranscriptionResponse(transcript);
    }

    private String callAzureSpeech(Path audioPath, String filename) {
        try {
            String boundary = "----EcobankQA" + UUID.randomUUID();
            String definitionJson = objectMapper.writeValueAsString(Map.of("locales", List.of(azureSpeechLanguage)));

            byte[] body = buildMultipartBody(boundary, filename, Files.readAllBytes(audioPath), definitionJson);

            String url = "https://" + azureSpeechRegion + ".api.cognitive.microsoft.com" +
                    "/speechtotext/transcriptions:transcribe?api-version=2024-11-15";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("Ocp-Apim-Subscription-Key", azureSpeechKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw ApiException.serviceUnavailable(
                        "Azure AI Speech a répondu HTTP " + response.statusCode() + " : " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode phrases = json.path("combinedPhrases");
            if (phrases.isArray() && !phrases.isEmpty()) {
                return phrases.get(0).path("text").asText("");
            }
            return "";

        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible de joindre Azure AI Speech : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Appel à Azure AI Speech interrompu.");
        }
    }

    private byte[] buildMultipartBody(String boundary, String filename, byte[] fileBytes, String definitionJson) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        String audioHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"audio\"; filename=\"" + filename + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n";
        out.write(audioHeader.getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);

        String definitionPart = "\r\n--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"definition\"\r\n" +
                "Content-Type: application/json\r\n\r\n" +
                definitionJson;
        out.write(definitionPart.getBytes(StandardCharsets.UTF_8));

        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Analyse — Azure OpenAI Service (chat completions)
    // ══════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public QualityAnalysisResponse analyze(Integer evaluationId, String transcript) {
        if (azureOpenAiEndpoint == null || azureOpenAiEndpoint.isBlank() || azureOpenAiKey == null || azureOpenAiKey.isBlank()) {
            throw ApiException.serviceUnavailable(
                    "Azure OpenAI Service n'est pas configuré (quality.ai.azure-openai-endpoint / -key). " +
                    "Contactez l'équipe infrastructure pour déployer la ressource et renseigner les identifiants.");
        }
        if (transcript == null || transcript.isBlank()) {
            throw ApiException.badRequest("Aucune transcription fournie à analyser.");
        }

        QualityEvaluation evaluation = findEvaluation(evaluationId);
        String prompt = buildAnalysisPrompt(evaluation, transcript);
        String analysis = callAzureOpenAi(prompt);

        return new QualityAnalysisResponse(analysis);
    }

    private String callAzureOpenAi(String userPrompt) {
        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "messages", List.of(
                            Map.of("role", "system", "content",
                                    "Tu es l'assistant qualité interne d'Ecobank. Tu analyses des transcriptions " +
                                    "d'appels selon la grille de critères et les procédures Ecobank fournies en " +
                                    "contexte. Réponds en français, de façon structurée et actionnable."),
                            Map.of("role", "user", "content", userPrompt)
                    ),
                    "temperature", 0.3,
                    "max_tokens", 800
            ));

            String url = azureOpenAiEndpoint.replaceAll("/$", "") +
                    "/openai/deployments/" + azureOpenAiDeployment +
                    "/chat/completions?api-version=2024-08-01-preview";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("api-key", azureOpenAiKey)
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

    /**
     * Contexte injecté dans le prompt : le référentiel de critères qualité Ecobank
     * (pour que l'analyse s'appuie sur la vraie grille de notation) + les procédures
     * dont le titre correspond au motif de l'appel, si un motif est renseigné.
     */
    private String buildAnalysisPrompt(QualityEvaluation evaluation, String transcript) {
        StringBuilder context = new StringBuilder();

        context.append("Grille d'évaluation qualité Ecobank :\n");
        List<QualityCriterion> criteria = criterionRepository.findAll();
        for (QualityCriterion c : criteria) {
            context.append("- ").append(c.getCode()).append(" (").append(c.getSection()).append(") : ")
                    .append(c.getName());
            if (Boolean.TRUE.equals(c.getIsKnockOut())) context.append(" — critère éliminatoire");
            context.append(" — pondération ").append(c.getWeight()).append("%\n");
        }

        if (evaluation.getMotif() != null) {
            List<Procedure> matches = procedureRepository
                    .findByTitleContainingIgnoreCaseOrderByTitleAsc(evaluation.getMotif().getLabel());
            if (!matches.isEmpty()) {
                context.append("\nProcédure(s) Ecobank liée(s) au motif \"")
                        .append(evaluation.getMotif().getLabel()).append("\" :\n");
                for (Procedure p : matches) {
                    context.append("Procédure : ").append(p.getTitle()).append("\n");
                    for (ProcedureStep step : procedureStepRepository.findByProcedureOrderByStepNumberAsc(p)) {
                        context.append("  ").append(step.getStepNumber()).append(". ").append(step.getContent()).append("\n");
                    }
                }
            }
        }

        return context + "\nAnalyse la transcription d'appel ci-dessous selon cette grille : indique pour " +
                "chaque section (Ouverture, Découverte, Traitement, Relation, Conclusion) si les points clés " +
                "semblent respectés, signale tout critère éliminatoire potentiellement non conforme, le ton de " +
                "l'agent, et propose 2 à 3 points d'amélioration concrets.\n\n" +
                "Transcription de l'appel :\n" + transcript;
    }

    private QualityEvaluation findEvaluation(Integer evaluationId) {
        return evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> ApiException.notFound("Évaluation introuvable."));
    }
}

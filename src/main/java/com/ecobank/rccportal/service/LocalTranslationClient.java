package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Client de traduction locale (Argos Translate via un script Python, voir scripts/translate.py)
 * — alternative hors-ligne aux sources réseau (Google/MyMemory), pour un environnement qui
 * bloque les domaines externes. Même schéma défensif que LocalOcrClient (déjà éprouvé sur ce
 * projet) :
 * <ul>
 *   <li>le texte est envoyé sur STDIN, jamais en argument — évite tout piège d'échappement
 *       shell avec des apostrophes/guillemets/accents dans le texte à traduire</li>
 *   <li>{@code waitFor()} avec timeout — un script qui bloque ne doit jamais faire pendre la
 *       requête HTTP indéfiniment</li>
 *   <li>{@link #isConfigured()} renvoie false tant que rcc.translation.offline.python-executable
 *       n'est pas renseigné, auquel cas l'appelant se rabat sur les sources réseau (ou
 *       inversement — voir RalphSearchService.translateDetailed(), qui essaie ceci EN
 *       PREMIER puisque ça ne dépend d'aucun accès internet)</li>
 * </ul>
 */
@Service
public class LocalTranslationClient {

    @Value("${rcc.translation.offline.python-executable:}")
    private String pythonExecutable;

    @Value("${rcc.translation.offline.script-path:}")
    private String scriptPath;

    @Value("${rcc.translation.offline.timeout-seconds:20}")
    private int timeoutSeconds;

    public boolean isConfigured() {
        return pythonExecutable != null && !pythonExecutable.isBlank()
                && scriptPath != null && !scriptPath.isBlank();
    }

    /** Résultat + langue source réellement utilisée (utile quand sourceLang="auto" — voir
     *  scripts/translate.py, qui retombe sur "fr" si langdetect n'est pas installé). */
    public record LocalTranslateResult(String translatedText, String detectedSourceLang) {}

    public LocalTranslateResult translate(String text, String sourceLang, String targetLang) {
        if (!isConfigured()) {
            throw ApiException.serviceUnavailable(
                    "Traduction locale non configurée (rcc.translation.offline.python-executable / script-path).");
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, scriptPath, sourceLang, targetLang);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Le texte part sur STDIN, jamais en argument — voir la javadoc de la classe.
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
            }

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw ApiException.serviceUnavailable("Traduction locale — délai dépassé (" + timeoutSeconds + "s).");
            }
            if (output == null || output.isBlank()) {
                throw ApiException.serviceUnavailable("Traduction locale n'a renvoyé aucune sortie.");
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(output);
            if (root.has("error")) {
                throw ApiException.serviceUnavailable("Traduction locale : " + root.get("error").asText());
            }
            String translated = root.path("translatedText").asText(null);
            String detected = root.path("detectedSourceLang").asText(sourceLang);
            if (translated == null) {
                throw ApiException.serviceUnavailable("Traduction locale — réponse inattendue : " + output);
            }
            return new LocalTranslateResult(translated, detected);

        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Erreur d'exécution de la traduction locale : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Traduction locale interrompue.");
        }
    }
}

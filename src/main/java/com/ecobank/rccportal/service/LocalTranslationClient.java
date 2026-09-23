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

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, scriptPath, sourceLang, targetLang);
            // UTF-8 forcé : sous Windows, Python lit/écrit par défaut en cp1252 — les accents
            // arrivaient corrompus dans le script et la sortie JSON (ensure_ascii=False) plantait
            // sur tout caractère hors cp1252 (arabe, chinois...).
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUTF8", "1");
            // stderr ignoré (Argos/Stanza y écrivent des avertissements) : sans ça, un tampon
            // stderr plein bloquait le script indéfiniment. Les erreurs utiles sortent en JSON sur stdout.
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            final Process running = process;

            // Lecture de stdout en tâche de fond : l'ancienne lecture bloquante rendait le
            // timeout inopérant (readLines() attendait la fin du script avant waitFor()).
            java.util.concurrent.CompletableFuture<String> stdout = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                } catch (IOException e) {
                    return "";
                }
            });

            // Le texte part sur STDIN, jamais en argument — voir la javadoc de la classe.
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw ApiException.serviceUnavailable("Traduction locale — délai dépassé (" + timeoutSeconds + "s).");
            }
            String output = stdout.get(5, TimeUnit.SECONDS);
            if (output == null || output.isBlank()) {
                throw ApiException.serviceUnavailable("Traduction locale n'a renvoyé aucune sortie (code " + process.exitValue() + ").");
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            // Seule la dernière ligne est le JSON de résultat (des bibliothèques peuvent imprimer avant).
            String lastLine = output.substring(output.lastIndexOf('\n') + 1).trim();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(lastLine);
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
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw ApiException.serviceUnavailable("Traduction locale — lecture de la sortie impossible.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Traduction locale interrompue.");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}

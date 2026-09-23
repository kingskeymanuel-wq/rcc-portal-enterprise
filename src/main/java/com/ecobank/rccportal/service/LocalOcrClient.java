package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Client OCR local (PaddleOCR via un script Python, voir scripts/ocr.py) — alternative
 * gratuite, sans cloud, à la lecture de capture d'écran par Claude (vision). Corrige les
 * pièges classiques d'un appel ProcessBuilder naïf :
 * <ul>
 *   <li>{@code MultipartFile.getOriginalFilename()} n'est PAS un chemin disque exploitable
 *       — le contenu doit d'abord être réellement écrit sur disque (fichier temporaire)</li>
 *   <li>{@code process.getInputStream()} doit être lu ET {@code waitFor()} appelé, avec un
 *       timeout — sinon un script qui bloque (ex. téléchargement de modèle au 1er lancement)
 *       fait pendre la requête HTTP indéfiniment</li>
 *   <li>le fichier temporaire doit être nettoyé dans tous les cas (succès, échec, timeout)</li>
 * </ul>
 * Non configuré par défaut — {@link #isConfigured()} renvoie false tant que
 * {@code rcc.ocr.python-executable} n'est pas renseigné, auquel cas l'appelant doit se
 * rabattre sur un autre moyen (Claude vision) sans jamais bloquer l'utilisateur.
 */
@Service
public class LocalOcrClient {

    @Value("${rcc.ocr.python-executable:}")
    private String pythonExecutable;

    @Value("${rcc.ocr.script-path:}")
    private String scriptPath;

    @Value("${rcc.ocr.timeout-seconds:60}")
    private int timeoutSeconds;

    public boolean isConfigured() {
        return pythonExecutable != null && !pythonExecutable.isBlank()
                && scriptPath != null && !scriptPath.isBlank();
    }

    /**
     * Lance l'OCR local sur une image et retourne la sortie JSON brute du script Python
     * (voir scripts/ocr.py pour le format exact : {"rawText": "...", "lines": [...]}).
     */
    public String extractRawJson(MultipartFile image) {
        if (!isConfigured()) {
            throw ApiException.serviceUnavailable(
                    "OCR local non configuré (rcc.ocr.python-executable / rcc.ocr.script-path).");
        }

        Path tempFile = null;
        try {
            // Étape indispensable et omise dans l'exemple naïf : le contenu du MultipartFile
            // doit être réellement écrit sur disque AVANT d'appeler le script — son nom seul
            // ne pointe vers rien d'exploitable côté serveur.
            String suffix = image.getOriginalFilename() != null && image.getOriginalFilename().contains(".")
                    ? image.getOriginalFilename().substring(image.getOriginalFilename().lastIndexOf('.')) : ".png";
            tempFile = Files.createTempFile("rcc-ocr-", suffix);
            Files.write(tempFile, image.getBytes());

            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, scriptPath, tempFile.toAbsolutePath().toString());
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            // Bug de l'exemple original : ne jamais lire la sortie sans attendre la fin du
            // processus avec un timeout — sinon un script qui bloque (ex. téléchargement de
            // modèle IA au premier lancement) fait pendre la requête HTTP indéfiniment.
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw ApiException.serviceUnavailable("OCR local — délai dépassé (" + timeoutSeconds + "s).");
            }
            if (process.exitValue() != 0) {
                throw ApiException.serviceUnavailable("OCR local a échoué (code " + process.exitValue() + ") : " + output);
            }
            if (output == null || output.isBlank()) {
                throw ApiException.serviceUnavailable("OCR local n'a renvoyé aucune sortie.");
            }
            return output;

        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Erreur d'exécution OCR local : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("OCR local interrompu.");
        } finally {
            // Nettoyage systématique — succès, échec, ou timeout : jamais de fichier
            // temporaire abandonné sur le serveur (contrairement à l'exemple original).
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) { }
            }
        }
    }
}

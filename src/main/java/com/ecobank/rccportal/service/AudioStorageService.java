package com.ecobank.rccportal.service;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
@Service
public class AudioStorageService {
    private static final List<String> ALLOWED_EXTENSIONS = List.of("mp3", "wav", "m4a", "ogg");
    @Value("${quality.audio.storage-dir}")
    private String storageDir;
    /** Sauvegarde le fichier sur disque et retourne l'URL relative à servir (/audio/{filename}). */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Audio file is required.");
        String original = file.getOriginalFilename();
        String extension = (original != null && original.contains("."))
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("Unsupported audio format. Allowed: " + ALLOWED_EXTENSIONS);
        }
        String filename = UUID.randomUUID() + "." + extension;
        try {
            Path dir = Path.of(storageDir);
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(filename));
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Failed to store audio file.");
        }
        return "/audio/" + filename;
    }
}
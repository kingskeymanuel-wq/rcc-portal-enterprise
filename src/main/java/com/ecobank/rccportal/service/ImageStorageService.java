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

/** Stockage des photos de profil — même principe que AudioStorageService. */
@Service
public class ImageStorageService {

    private static final List<String> ALLOWED_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp", "gif", "mp4", "mov");
    private static final long MAX_SIZE_BYTES = 50L * 1024 * 1024; // 50 Mo — photo ou courte vidéo

    @Value("${rcc.uploads.photos-dir}")
    private String storageDir;

    /** Sauvegarde le fichier sur disque et retourne l'URL relative à servir (/uploaded-photos/{filename}). */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Image file is required.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw ApiException.badRequest("Image too large (max 5 MB).");
        }

        String original = file.getOriginalFilename();
        String extension = (original != null && original.contains("."))
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("Unsupported image format. Allowed: " + ALLOWED_EXTENSIONS);
        }

        String filename = UUID.randomUUID() + "." + extension;
        try {
            Path dir = Path.of(storageDir);
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(filename));
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Failed to store image file.");
        }

        return "/uploaded-photos/" + filename;
    }
}

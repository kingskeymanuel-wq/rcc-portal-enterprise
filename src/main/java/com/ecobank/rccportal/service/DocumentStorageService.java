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

/** Stockage des fichiers joints à la Base de connaissances — même principe que Audio/ImageStorageService. */
@Service
public class DocumentStorageService {

    private static final List<String> ALLOWED_EXTENSIONS = List.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "png", "jpg", "jpeg"
    );
    private static final long MAX_SIZE_BYTES = 20L * 1024 * 1024; // 20 Mo

    @Value("${rcc.uploads.kb-files-dir}")
    private String storageDir;

    /** Sauvegarde le fichier sur disque et retourne l'URL relative à servir (/kb-files/{filename}). */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("File is required.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw ApiException.badRequest("File too large (max 20 MB).");
        }

        String original = file.getOriginalFilename();
        String extension = (original != null && original.contains("."))
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("Unsupported file format. Allowed: " + ALLOWED_EXTENSIONS);
        }

        String filename = UUID.randomUUID() + "." + extension;
        try {
            Path dir = Path.of(storageDir);
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(filename));
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Failed to store file.");
        }

        return "/kb-files/" + filename;
    }
}

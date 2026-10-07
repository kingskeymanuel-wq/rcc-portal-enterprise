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

    /**
     * Relit un document déjà stocké (/kb-files/…) — génération d'une évaluation à partir du support d'un cours.
     * Refuse tout chemin hors du dossier de stockage.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ImageStorageService uploads;

    public MultipartFile open(String storageUrl, String originalName) {
        if (storageUrl != null && storageUrl.startsWith("/uploaded-photos/") && uploads != null) {
            // Vidéo téléversée du cours (même dossier que les photos)
            java.nio.file.Path p = uploads.resolve(storageUrl);
            try {
                String display = originalName != null && !originalName.isBlank() ? originalName : p.getFileName().toString();
                return new StoredFile(display, Files.readAllBytes(p));
            } catch (IOException e) {
                throw ApiException.serviceUnavailable("Lecture du fichier impossible.");
            }
        }
        if (storageUrl == null || !storageUrl.startsWith("/kb-files/")) throw ApiException.badRequest("Document inconnu.");
        String name = storageUrl.substring("/kb-files/".length());
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) throw ApiException.badRequest("Document inconnu.");
        Path path = Path.of(storageDir).resolve(name).normalize();
        if (!path.startsWith(Path.of(storageDir).normalize()) || !Files.isRegularFile(path)) {
            throw ApiException.notFound("Le document du cours est introuvable sur le serveur.");
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            String display = originalName != null && !originalName.isBlank() ? originalName : name;
            return new StoredFile(display, bytes);
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Lecture du document impossible.");
        }
    }

    /** Fichier déjà sur disque présenté comme un envoi (même extraction de texte que pour un fichier déposé). */
    private record StoredFile(String originalName, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalName; }
        @Override public String getContentType() { return null; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
        @Override public void transferTo(java.io.File dest) throws IOException { Files.write(dest.toPath(), bytes); }
    }
}

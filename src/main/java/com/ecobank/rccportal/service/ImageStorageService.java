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

    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp", "gif");
    /** Formats vidéo lisibles par les navigateurs du portail (mp4/m4v/mov = H.264, webm). */
    private static final List<String> VIDEO_EXTENSIONS = List.of("mp4", "m4v", "mov", "webm");
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024; // 20 Mo

    /** Taille max d'une vidéo (Mo) — aligner avec spring.servlet.multipart.max-file-size. */
    @Value("${rcc.uploads.max-video-mb:500}")
    private long maxVideoMb;

    @Value("${rcc.uploads.photos-dir}")
    private String storageDir;

    /** Sauvegarde le fichier sur disque et retourne l'URL relative à servir (/uploaded-photos/{filename}). */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Aucun fichier reçu.");
        }
        String original = file.getOriginalFilename();
        String extension = (original != null && original.contains("."))
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase()
                : extensionFromContentType(file.getContentType());
        boolean video = VIDEO_EXTENSIONS.contains(extension);
        if (!video && !IMAGE_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("Format non pris en charge (." + extension + "). Photos : " + IMAGE_EXTENSIONS
                    + " — vidéos : " + VIDEO_EXTENSIONS + ". Les vidéos iPhone (.heic/.hevc) doivent être exportées en MP4.");
        }
        long max = video ? maxVideoMb * 1024 * 1024 : MAX_IMAGE_BYTES;
        if (file.getSize() > max) {
            throw ApiException.badRequest((video ? "Vidéo" : "Photo") + " trop volumineuse (" + (file.getSize() / (1024 * 1024))
                    + " Mo, maximum " + (max / (1024 * 1024)) + " Mo).");
        }

        String filename = UUID.randomUUID() + "." + extension;
        try {
            Path dir = Path.of(storageDir);
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(filename)); // copie en flux, sans charger la vidéo en mémoire
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Impossible d'enregistrer le fichier sur le serveur (espace disque ou droits du dossier d'upload).");
        }

        return "/uploaded-photos/" + filename;
    }

    private static String extensionFromContentType(String contentType) {
        if (contentType == null) return "";
        return switch (contentType.toLowerCase()) {
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            case "video/webm" -> "webm";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "";
        };
    }
}

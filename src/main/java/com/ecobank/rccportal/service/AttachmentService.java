package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AttachmentRequest;
import com.ecobank.rccportal.dto.AttachmentResponse;
import com.ecobank.rccportal.model.Attachment;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AttachmentRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

/**
 * Pièces jointes génériques (association polymorphe EntityType/EntityId — voir
 * db/rcc-full-schema/01_schema.sql). Un seul service réutilisable par tous les
 * domaines (Procedures aujourd'hui ; AgentDossiers, MailTemplates plus tard),
 * plutôt qu'un service dupliqué par domaine.
 *
 * ⚠️ Ne gère PAS l'upload de fichier lui-même (pas de MultipartFile ici) —
 * `storageUrl` est fourni par l'appelant, qui doit avoir déjà déposé le fichier
 * sur un stockage réel (ex. Azure Blob Storage, S3, ou un dossier partagé) et
 * en connaître l'URL. Câbler un vrai endpoint d'upload est une prochaine étape.
 */
@Service
public class AttachmentService {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https");

    private final AttachmentRepository attachmentRepository;
    private final UserRepository userRepository;
    private final com.ecobank.rccportal.repository.FavoriteAttachmentRepository favoriteAttachmentRepository;

    public AttachmentService(AttachmentRepository attachmentRepository, UserRepository userRepository,
                             com.ecobank.rccportal.repository.FavoriteAttachmentRepository favoriteAttachmentRepository) {
        this.attachmentRepository = attachmentRepository;
        this.userRepository = userRepository;
        this.favoriteAttachmentRepository = favoriteAttachmentRepository;
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> listFor(String entityType, Integer entityId) {
        return listFor(entityType, entityId, null);
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> listFor(String entityType, Integer entityId, String viewerusername) {
        User viewer = viewerusername != null
                ? userRepository.findFirstByUsernameIgnoreCase(viewerusername).orElse(null) : null;
        return attachmentRepository.findByEntityTypeAndEntityId(entityType, entityId).stream()
                .map(a -> toResponse(a, viewer)).toList();
    }

    @Transactional
    public void toggleFavorite(Integer attachmentId, String username, boolean favorite) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound("Attachment not found."));
        User user = userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        if (favorite) {
            if (!favoriteAttachmentRepository.existsByUserAndAttachment(user, attachment)) {
                favoriteAttachmentRepository.save(com.ecobank.rccportal.model.FavoriteAttachment.builder()
                        .user(user).attachment(attachment).build());
            }
        } else {
            favoriteAttachmentRepository.deleteByUserAndAttachment(user, attachment);
        }
    }

    @Transactional
    public AttachmentResponse attach(String entityType, Integer entityId, AttachmentRequest request, String uploaderusername) {
        if (request.fileName() == null || request.fileName().isBlank()) throw ApiException.badRequest("fileName is required.");
        if (request.fileName().length() > 260) throw ApiException.badRequest("fileName must be at most 260 characters.");
        if (request.mimeType() != null && request.mimeType().length() > 100) {
            throw ApiException.badRequest("mimeType must be at most 100 characters.");
        }
        validateStorageUrl(request.storageUrl());

        User uploader = userRepository.findFirstByUsernameIgnoreCase(uploaderusername).orElse(null); // null toléré (upload système)
        Attachment attachment = Attachment.builder()
                .entityType(entityType).entityId(entityId)
                .fileName(request.fileName()).mimeType(request.mimeType()).storageUrl(request.storageUrl())
                .uploadedBy(uploader)
                .build();
        return toResponse(attachmentRepository.save(attachment), null);
    }

    /**
     * Pour un fichier réellement uploadé sur ce serveur (DocumentStorageService/
     * ImageStorageService/AudioStorageService) : storageUrl est une URL relative
     * générée en interne (ex. "/kb-files/xxx.pdf"), jamais fournie par l'appelant
     * — inutile et impossible de lui appliquer la validation https ci-dessous.
     */
    @Transactional
    public AttachmentResponse attachLocalFile(String entityType, Integer entityId,
                                               String fileName, String mimeType, String relativeStorageUrl,
                                               String uploaderusername) {
        if (fileName == null || fileName.isBlank()) throw ApiException.badRequest("fileName is required.");

        User uploader = userRepository.findFirstByUsernameIgnoreCase(uploaderusername).orElse(null);
        Attachment attachment = Attachment.builder()
                .entityType(entityType).entityId(entityId)
                .fileName(fileName).mimeType(mimeType).storageUrl(relativeStorageUrl)
                .uploadedBy(uploader)
                .build();
        return toResponse(attachmentRepository.save(attachment), null);
    }

    @Transactional
    public void remove(Integer attachmentId, AuthenticatedUser requester) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound("Attachment not found."));
        boolean isUploader = attachment.getUploadedBy() != null
                && requester.username().equals(attachment.getUploadedBy().getUsername());
        if (!"admin".equalsIgnoreCase(requester.role()) && !isUploader) {
            throw ApiException.forbidden("You can only remove your own attachments.");
        }
        attachmentRepository.delete(attachment);
    }

    /** N'accepte que des URL https bien formées, de longueur compatible avec la colonne StorageUrl (500). */
    private void validateStorageUrl(String storageUrl) {
        if (storageUrl == null || storageUrl.isBlank()) throw ApiException.badRequest("storageUrl is required.");
        if (storageUrl.length() > 500) throw ApiException.badRequest("storageUrl must be at most 500 characters.");
        URI uri;
        try {
            uri = new URI(storageUrl);
        } catch (URISyntaxException e) {
            throw ApiException.badRequest("storageUrl is not a valid URL.");
        }
        if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
            throw ApiException.badRequest("storageUrl must be an https URL.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw ApiException.badRequest("storageUrl must be an absolute URL with a host.");
        }
    }

    private AttachmentResponse toResponse(Attachment a, User viewer) {
        boolean isFavorite = viewer != null && favoriteAttachmentRepository.existsByUserAndAttachment(viewer, a);
        return new AttachmentResponse(a.getAttachmentId(), a.getEntityType(), a.getEntityId(), a.getFileName(),
                a.getMimeType(), a.getStorageUrl(),
                a.getUploadedBy() != null ? a.getUploadedBy().getUsername() : null, a.getCreatedAt(), isFavorite);
    }
}

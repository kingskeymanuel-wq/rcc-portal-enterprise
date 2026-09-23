package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.AttachmentResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AttachmentService;
import com.ecobank.rccportal.service.DocumentStorageService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Dossier documentaire par collaborateur (pièce d'identité, copie de contrat, etc.) — vue
 * "Collaborateurs" du portail RH. Réutilise le mécanisme générique Attachments
 * (EntityType/EntityId, voir AttachmentService) déjà en place pour la Base de connaissance,
 * avec EntityType = "USER_HR_DOSSIER" et EntityId = l'ID de l'utilisateur concerné. Fichier
 * réellement stocké sur ce serveur via DocumentStorageService (même stockage que les fichiers
 * de la Base de connaissance — mêmes formats/tailles autorisés).
 */
@RestController
@RequestMapping("/api/hr/dossiers")
public class HrDossierController {

    private static final String ENTITY_TYPE = "USER_HR_DOSSIER";

    private final AttachmentService attachmentService;
    private final DocumentStorageService documentStorageService;

    public HrDossierController(AttachmentService attachmentService, DocumentStorageService documentStorageService) {
        this.attachmentService = attachmentService;
        this.documentStorageService = documentStorageService;
    }

    @GetMapping("/{userId}")
    public List<AttachmentResponse> list(@PathVariable Long userId,
                                         @AuthenticationPrincipal AuthenticatedUser requester) {
        requireHrOrAdmin(requester);
        return attachmentService.listFor(ENTITY_TYPE, toEntityId(userId));
    }

    /** Upload réel — le fichier est stocké sur ce serveur, pas juste référencé par une URL externe. */
    @PostMapping(value = "/{userId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@PathVariable Long userId,
                                     @RequestParam("file") MultipartFile file,
                                     @AuthenticationPrincipal AuthenticatedUser requester) {
        requireHrOrAdmin(requester);
        String storageUrl = documentStorageService.store(file);
        return attachmentService.attachLocalFile(
                ENTITY_TYPE, toEntityId(userId), file.getOriginalFilename(), file.getContentType(),
                storageUrl, requester.username());
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Integer attachmentId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireHrOrAdmin(requester);
        attachmentService.remove(attachmentId, requester);
    }

    private Integer toEntityId(Long userId) {
        try {
            return Math.toIntExact(userId);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("Invalid user id.");
        }
    }

    private void requireHrOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        if (!isAdmin && !isRh) {
            throw ApiException.forbidden("Only RH or an administrator can manage HR dossiers.");
        }
    }
}

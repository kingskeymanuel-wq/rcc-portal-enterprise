package com.ecobank.rccportal.controller;
import com.ecobank.rccportal.service.FavoriteProcedureService;
import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AttachmentService;
import com.ecobank.rccportal.service.ProcedureService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/procedures")
public class ProcedureController {

    private static final String ATTACHMENT_ENTITY_TYPE = "Procedure";

    private final ProcedureService procedureService;
    private final FavoriteProcedureService favoriteProcedureService;
    private final AttachmentService attachmentService;
    private final com.ecobank.rccportal.service.DocumentTextExtractionService documentTextExtractionService;
    private final com.ecobank.rccportal.service.DocumentStorageService documentStorageService;

    public ProcedureController(
            ProcedureService procedureService,
            AttachmentService attachmentService,
            FavoriteProcedureService favoriteProcedureService,
            com.ecobank.rccportal.service.DocumentTextExtractionService documentTextExtractionService,
            com.ecobank.rccportal.service.DocumentStorageService documentStorageService) {

        this.procedureService = procedureService;
        this.documentStorageService = documentStorageService;
        this.attachmentService = attachmentService;
        this.favoriteProcedureService = favoriteProcedureService;
        this.documentTextExtractionService = documentTextExtractionService;
    }
    @GetMapping("/zones")
    public List<ProcedureZoneResponse> listZones(@RequestParam(required = false) String team,
                                                  @AuthenticationPrincipal AuthenticatedUser requester) {
        if (team != null && !team.isBlank()) {
            return procedureService.listZones(team);
        }
        return procedureService.listZonesFor(requester);
    }

    /** Ouvert à tout utilisateur connecté (contrairement à /api/admin/services) — juste pour peupler un sélecteur. */
    @GetMapping("/services")
    public List<RccServiceResponse> listServices() {
        return procedureService.listServices();
    }

    /** Création d'une catégorie/zone (ex. "COMPTE") — réservé à QA/admin. */
    @PostMapping("/zones")
    @ResponseStatus(HttpStatus.CREATED)
    public ProcedureZoneResponse createZone(@RequestBody CreateProcedureZoneRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return procedureService.createZone(request);
    }

    @PostMapping(value = "/zones/{id}/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProcedureZoneResponse uploadZoneImage(@PathVariable Integer id,
                                                 @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                                 @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return procedureService.updateZoneImage(id, file);
    }

    @GetMapping
    public List<ProcedureSummaryResponse> listAll(
            @RequestParam(required = false) String zone,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        return zone != null
                ? procedureService.listByZone(zone, requester)
                : procedureService.listAll(requester);
    }

    @GetMapping("/{id}")
    public ProcedureResponse getById(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        return procedureService.getById(id, requester.username());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProcedureResponse create(
            @Valid @RequestBody ProcedureRequest request,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        requireQaOnly(requester);
        return procedureService.create(request, requester);
    }

    /**
     * Import intelligent — au lieu de taper la fiche à la main, on dépose un fichier
     * (PDF, Word, texte) et le titre + les étapes en sont extraits automatiquement.
     * La zone (et le service, optionnel) restent à choisir : impossibles à deviner
     * fiablement depuis le contenu seul.
     */
    @PostMapping(value = "/import-document", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<ProcedureResponse> importDocument(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam String zoneCode,
            @RequestParam(required = false) String serviceCode,
            @RequestParam(required = false) String countryCode,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        requireQaOnly(requester);
        var extractedSections = documentTextExtractionService.extractMultiple(file);
        List<ProcedureResponse> created = new java.util.ArrayList<>();
        for (var extracted : extractedSections) {
            if (extracted.suggestedSteps().isEmpty()) continue;
            ProcedureRequest request = new ProcedureRequest(
                    zoneCode, serviceCode, countryCode, extracted.suggestedTitle(),
                    null, null, null, extracted.suggestedSteps());
            created.add(procedureService.create(request, requester));
        }
        if (created.isEmpty()) {
            throw ApiException.badRequest("Aucune étape n'a pu être identifiée dans ce document.");
        }
        return created;
    }

    @PutMapping("/{id}")
    public ProcedureResponse update(
            @PathVariable Integer id,
            @RequestBody ProcedureRequest request,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        return procedureService.update(id, request, requester);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        procedureService.remove(id, requester);
    }

    @GetMapping("/dashboard")
    public ProcedureDashboardResponse dashboard() {
        return procedureService.dashboard();
    }

    @GetMapping("/search")
    public List<ProcedureSummaryResponse> search(
            @RequestParam String q,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        return procedureService.search(q, requester);
    }
    @PostMapping("/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addFavorite(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        favoriteProcedureService.addFavorite(id, requester);
    }

    @DeleteMapping("/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFavorite(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        favoriteProcedureService.removeFavorite(id, requester);
    }

    @GetMapping("/{id}/favorite")
    public boolean isFavorite(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        return favoriteProcedureService.isFavorite(id, requester);
    }
    // ------------------- ATTACHMENTS -------------------

    @GetMapping("/{id}/attachments")
    public List<AttachmentResponse> listAttachments(
            @PathVariable Integer id,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        return attachmentService.listFor(ATTACHMENT_ENTITY_TYPE, id, requester != null ? requester.username() : null);
    }

    @PostMapping("/attachments/{attachmentId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void favoriteAttachment(@PathVariable Integer attachmentId, @AuthenticationPrincipal AuthenticatedUser requester) {
        attachmentService.toggleFavorite(attachmentId, requester.username(), true);
    }

    @DeleteMapping("/attachments/{attachmentId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfavoriteAttachment(@PathVariable Integer attachmentId, @AuthenticationPrincipal AuthenticatedUser requester) {
        attachmentService.toggleFavorite(attachmentId, requester.username(), false);
    }

    @PostMapping(value = "/{id}/attachments/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse uploadAttachment(
            @PathVariable Integer id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        requireQaOnly(requester);
        String storageUrl = documentStorageService.store(file);
        return attachmentService.attachLocalFile(
                ATTACHMENT_ENTITY_TYPE, id, file.getOriginalFilename(), file.getContentType(), storageUrl, requester.username());
    }

    /**
     * Import rapide depuis une vignette de thématique — pas besoin de créer/choisir une fiche au
     * préalable. Résout (ou crée) le dossier conteneur pour ce triplet zone/service/filiale, y
     * attache le fichier. L'agent le retrouve ensuite en filtrant sur cette filiale/service, comme
     * n'importe quelle autre fiche.
     */
    @PostMapping(value = "/zones/{zoneCode}/quick-upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse quickUpload(
            @PathVariable String zoneCode,
            @RequestParam(required = false) String serviceCode,
            @RequestParam(required = false) String countryCode,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        requireQaOnly(requester);
        var container = procedureService.findOrCreateContainer(zoneCode, serviceCode, countryCode, requester);
        String storageUrl = documentStorageService.store(file);
        return attachmentService.attachLocalFile(
                ATTACHMENT_ENTITY_TYPE, container.getProcedureId(), file.getOriginalFilename(), file.getContentType(), storageUrl, requester.username());
    }

    @PostMapping("/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse addAttachment(
            @PathVariable Integer id,
            @Valid @RequestBody AttachmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        requireQaOnly(requester);
        return attachmentService.attach(
                ATTACHMENT_ENTITY_TYPE,
                id,
                request,
                requester.username()
        );
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAttachment(
            @PathVariable Integer attachmentId,
            @AuthenticationPrincipal AuthenticatedUser requester) {

        requireQaOnly(requester);
        attachmentService.remove(attachmentId, requester);
    }

    /** Zones/catégories — structurel, reste QA ou admin. */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isQa = (requester.service() != null && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' ')));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can create a zone.");
        }
    }

    /** ⚠️ Contenu (procédures) — réservé à la Quality Assurance, l'admin s'occupe des réglages. */
    private void requireQaOnly(AuthenticatedUser requester) {
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isQa) {
            throw ApiException.forbidden("Only Quality Assurance can manage procedures.");
        }
    }

}
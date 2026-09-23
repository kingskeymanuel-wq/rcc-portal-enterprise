package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.KnowledgeArticle;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AttachmentService;
import com.ecobank.rccportal.service.DocumentStorageService;
import com.ecobank.rccportal.service.KnowledgeService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/kb")
public class KnowledgeApiController {

    private static final String ATTACHMENT_ENTITY_TYPE = "KnowledgeArticle";

    private final KnowledgeService knowledgeService;
    private final AttachmentService attachmentService;
    private final DocumentStorageService documentStorageService;
    private final com.ecobank.rccportal.service.DocumentTextExtractionService documentTextExtractionService;

    public KnowledgeApiController(KnowledgeService knowledgeService, AttachmentService attachmentService,
                                  DocumentStorageService documentStorageService,
                                  com.ecobank.rccportal.service.DocumentTextExtractionService documentTextExtractionService) {
        this.knowledgeService = knowledgeService;
        this.attachmentService = attachmentService;
        this.documentStorageService = documentStorageService;
        this.documentTextExtractionService = documentTextExtractionService;
    }

    @GetMapping("/categories")
    public List<KnowledgeCategoryResponse> categories(@RequestParam(required = false) String team,
                                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        if (team != null && !team.isBlank()) {
            return knowledgeService.listCategories(team);
        }
        return knowledgeService.listCategoriesFor(requester);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeCategoryResponse createCategory(@RequestBody KnowledgeCategoryRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return knowledgeService.createCategory(request);
    }

    @PutMapping("/categories/{id}")
    public KnowledgeCategoryResponse updateCategory(@PathVariable Integer id, @RequestBody KnowledgeCategoryRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return knowledgeService.updateCategory(id, request);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable Integer id, @RequestParam(defaultValue = "false") boolean force,
                               @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        knowledgeService.deleteCategory(id, force);
    }

    @GetMapping("/countries")
    public List<KnowledgeCountryResponse> countries() {
        return knowledgeService.listCountries();
    }

    @PostMapping("/countries")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeCountryResponse createCountry(@RequestBody KnowledgeCountryRequest request,
                                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return knowledgeService.createCountry(request);
    }

    /** countryCode omis → articles génériques (procédures/scripts valables partout). */
    @GetMapping("/articles")
    public List<KnowledgeArticleResponse> listArticles(@RequestParam Integer categoryId,
                                                        @RequestParam(required = false) String countryCode,
                                                        @RequestParam(required = false) String serviceCode) {
        return knowledgeService.listArticles(categoryId, countryCode, serviceCode);
    }

    @GetMapping("/articles/search")
    public List<KnowledgeArticleResponse> search(@RequestParam String q) {
        return knowledgeService.search(q);
    }

    /** Lien profond depuis la recherche globale (session.js) : l'article seul ne suffit pas à
     *  reconstruire l'URL /api/kb/articles?categoryId=... côté client — on renvoie ici sa
     *  catégorie et sa filiale pour que le front puisse d'abord positionner le bon contexte
     *  (modale pays réutilisée, voir knowledge.js) avant d'ouvrir l'article lui-même. */
    @GetMapping("/articles/{id}")
    public KnowledgeArticleResponse getArticle(@PathVariable Integer id) {
        return knowledgeService.getArticle(id);
    }

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeArticleResponse createArticle(@RequestBody KnowledgeArticleRequest request,
                                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return knowledgeService.createArticle(request, null);
    }

    /**
     * Import intelligent — dépose un PDF/Word/texte, le titre et le contenu sont extraits
     * automatiquement (même principe que ProcedureController.importDocument). Si plusieurs
     * sections distinctes sont détectées dans le document, un article est créé par section.
     */
    @PostMapping(value = "/articles/import-document", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<KnowledgeArticleResponse> importDocument(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam Integer categoryId,
            @RequestParam(required = false) String countryCode,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);

        var extractedSections = documentTextExtractionService.extractMultiple(file);
        List<KnowledgeArticleResponse> created = new java.util.ArrayList<>();
        for (var extracted : extractedSections) {
            if (extracted.rawText().isBlank()) continue;
            String contentHtml = "<p>" + extracted.rawText().replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\n", "</p><p>") + "</p>";
            KnowledgeArticleRequest request = new KnowledgeArticleRequest(
                    categoryId, countryCode, null, extracted.suggestedTitle(), contentHtml, null, 0);
            created.add(knowledgeService.createArticle(request, null));
        }
        if (created.isEmpty()) {
            throw ApiException.badRequest("Aucun contenu n'a pu être extrait de ce document.");
        }
        return created;
    }

    @PutMapping("/articles/{id}")
    public KnowledgeArticleResponse updateArticle(@PathVariable Integer id, @RequestBody KnowledgeArticleRequest request,
                                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return knowledgeService.updateArticle(id, request);
    }

    @DeleteMapping("/articles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeArticle(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        knowledgeService.removeArticle(id);
    }

    @PostMapping(value = "/categories/{id}/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeCategoryResponse uploadCategoryImage(@PathVariable Integer id,
                                                          @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return knowledgeService.updateCategoryImage(id, file);
    }

    // ------------------- FICHIERS JOINTS -------------------

    @GetMapping("/articles/{id}/attachments")
    public List<AttachmentResponse> listAttachments(@PathVariable Integer id) {
        return attachmentService.listFor(ATTACHMENT_ENTITY_TYPE, id);
    }

    /** Upload réel — le fichier est stocké sur ce serveur, pas juste référencé par une URL externe. */
    @PostMapping("/articles/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse uploadAttachment(@PathVariable Integer id,
                                               @RequestParam("file") MultipartFile file,
                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        String storageUrl = documentStorageService.store(file);
        return attachmentService.attachLocalFile(
                ATTACHMENT_ENTITY_TYPE, id, file.getOriginalFilename(), file.getContentType(), storageUrl, requester.username());
    }

    /** Lien externe (https) plutôt qu'un fichier réellement hébergé ici — même mécanisme déjà
     *  en place pour les Procédures (voir ProcedureController), étendu à la Base de connaissances. */
    @PostMapping("/articles/{id}/link")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse attachLink(@PathVariable Integer id,
                                         @jakarta.validation.Valid @RequestBody com.ecobank.rccportal.dto.AttachmentRequest request,
                                         @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return attachmentService.attach(ATTACHMENT_ENTITY_TYPE, id, request, requester.username());
    }

    /**
     * Import rapide depuis une vignette de rubrique — pas besoin de créer un article au préalable.
     * Résout (ou crée) le dossier conteneur pour ce triplet catégorie/service/filiale, y attache le
     * fichier. Visible ensuite pour l'agent en filtrant filiale/service, comme un article normal.
     */
    @PostMapping(value = "/categories/{categoryId}/quick-upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse quickUpload(@PathVariable Integer categoryId,
                                          @RequestParam(required = false) String serviceCode,
                                          @RequestParam(required = false) String countryCode,
                                          @RequestParam("file") MultipartFile file,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        KnowledgeArticle container = knowledgeService.findOrCreateContainer(categoryId, serviceCode, countryCode, null);
        String storageUrl = documentStorageService.store(file);
        return attachmentService.attachLocalFile(
                ATTACHMENT_ENTITY_TYPE, container.getArticleId(), file.getOriginalFilename(), file.getContentType(), storageUrl, requester.username());
    }

    /** Équivalent lien du quick-upload ci-dessus — même vignette de rubrique, mais un lien
     *  externe (https) au lieu d'un fichier réellement hébergé ici. */
    @PostMapping("/categories/{categoryId}/quick-upload-link")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse quickUploadLink(@PathVariable Integer categoryId,
                                              @RequestParam(required = false) String serviceCode,
                                              @RequestParam(required = false) String countryCode,
                                              @jakarta.validation.Valid @RequestBody com.ecobank.rccportal.dto.AttachmentRequest request,
                                              @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        KnowledgeArticle container = knowledgeService.findOrCreateContainer(categoryId, serviceCode, countryCode, null);
        return attachmentService.attach(ATTACHMENT_ENTITY_TYPE, container.getArticleId(), request, requester.username());
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAttachment(@PathVariable Integer attachmentId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        attachmentService.remove(attachmentId, requester);
    }

    /** Catégories/filiales — structurel, reste QA ou admin. */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && (requester.service() != null && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' ')));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can manage the knowledge base.");
        }
    }

    /** ⚠️ Articles/pièces jointes — contenu réel, réservé à la Quality Assurance (admin exclu). */
    private void requireQaOnly(AuthenticatedUser requester) {
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isQa) {
            throw ApiException.forbidden("Only Quality Assurance can manage knowledge base articles.");
        }
    }

    /** Insertion d'image — réservé à l'administrateur uniquement, même la QA ne peut pas. */
    private void requireAdmin(AuthenticatedUser requester) {
        if (requester == null || !"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only an administrator can upload images.");
        }
    }
}
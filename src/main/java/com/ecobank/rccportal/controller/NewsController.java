package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.NewsArticleRequest;
import com.ecobank.rccportal.dto.NewsArticleResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.DocumentStorageService;
import com.ecobank.rccportal.service.NewsService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsService newsService;
    private final DocumentStorageService documentStorageService;

    public NewsController(NewsService newsService, DocumentStorageService documentStorageService) {
        this.newsService = newsService;
        this.documentStorageService = documentStorageService;
    }

    /** Ouvert à tous les profils connectés — visible sur la page d'accueil de tous les portails. */
    @GetMapping
    public List<NewsArticleResponse> listAll() {
        return newsService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NewsArticleResponse create(@RequestBody NewsArticleRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return newsService.create(request, requester.username());
    }

    @PutMapping("/{id}")
    public NewsArticleResponse update(@PathVariable Integer id, @RequestBody NewsArticleRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return newsService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        newsService.delete(id);
    }

    /** Upload réel de la photo d'illustration — pas un lien externe. */
    @PostMapping(value = "/upload-image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public java.util.Map<String, String> uploadImage(@RequestParam("file") MultipartFile file,
                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        String url = documentStorageService.store(file);
        return java.util.Map.of("url", url);
    }

    /** Réservé QA/admin, et au service "Communication" (habilité à publier sur MON RCC — voir
     *  WorkflowSchemaBootstrap pour la création de ce service). */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && java.util.Set.of("quality assurance", "superviseur qa", "communication").contains(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can manage news articles.");
        }
    }
}

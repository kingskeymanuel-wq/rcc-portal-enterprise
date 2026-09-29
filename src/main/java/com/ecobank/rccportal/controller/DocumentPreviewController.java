package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.DocumentPreviewService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Aperçu d'un document de la base de connaissances, affiché dans la fenêtre du portail (sans téléchargement). */
@RestController
public class DocumentPreviewController {

    private final DocumentPreviewService previews;

    public DocumentPreviewController(DocumentPreviewService previews) {
        this.previews = previews;
    }

    @GetMapping("/api/kb/files/preview")
    public DocumentPreviewService.Preview preview(@RequestParam String url, @RequestParam(required = false) String name,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) throw ApiException.unauthorized("Connexion requise.");
        return previews.preview(url, name);
    }
}

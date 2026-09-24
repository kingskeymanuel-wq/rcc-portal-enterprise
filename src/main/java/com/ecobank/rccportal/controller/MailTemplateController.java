package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.MailTemplateRequest;
import com.ecobank.rccportal.dto.MailTemplateResponse;
import com.ecobank.rccportal.dto.MailTemplatesOverviewResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.MailTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Portage direct de src/controllers/mail-template.controller.js. */
@RestController
@RequestMapping("/api/mail-templates")
public class MailTemplateController {

    private final MailTemplateService mailTemplateService;

    public MailTemplateController(MailTemplateService mailTemplateService) {
        this.mailTemplateService = mailTemplateService;
    }

    @GetMapping
    public MailTemplatesOverviewResponse listAll(@AuthenticationPrincipal AuthenticatedUser requester) {
        return mailTemplateService.listAll(requester);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MailTemplateResponse create(@Valid @RequestBody MailTemplateRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        return mailTemplateService.create(request, requester);
    }

    @PutMapping("/{id}")
    public MailTemplateResponse update(@PathVariable Integer id, @RequestBody MailTemplateRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        return mailTemplateService.update(id, request, requester);
    }

    /** Classe une catégorie dans une équipe (EMAIL/RAFIKI/SOCIAL) — QA uniquement. */
    @PutMapping("/categories/{id}/team")
    public void assignCategoryTeam(@PathVariable Integer id, @RequestBody java.util.Map<String, String> body,
                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        mailTemplateService.assignCategoryTeam(id, body.get("team"), requester);
    }

    /** Crée une nouvelle catégorie, directement classée dans une équipe — QA uniquement. */
    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public MailTemplatesOverviewResponse.CategoryDto createCategory(@RequestBody java.util.Map<String, String> body,
                                                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        return mailTemplateService.createCategory(body.get("label"), body.get("team"), body.get("iconGlyph"), body.get("accentColor"), requester);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        mailTemplateService.remove(id, requester);
    }

    /** Balises [XXX] du masque — pour générer le formulaire "Coordonnées du client". Masque personnel : réservé à son auteur. */
    @GetMapping("/{id}/placeholders")
    public java.util.List<String> placeholders(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return mailTemplateService.listPlaceholders(id, requester);
    }

    /** Remplissage automatique — masques communs ouverts à tous, masque personnel réservé à son auteur. */
    @PostMapping("/{id}/fill")
    public com.ecobank.rccportal.dto.MailTemplateFillResponse fill(@PathVariable Integer id,
                                                                    @RequestBody com.ecobank.rccportal.dto.MailTemplateFillRequest request,
                                                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        return mailTemplateService.fill(id, request.values(), requester);
    }
}

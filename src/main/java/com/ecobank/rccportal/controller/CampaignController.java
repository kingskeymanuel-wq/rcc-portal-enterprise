package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.CampaignService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaignService;
    private final com.ecobank.rccportal.service.CampaignResultsService results;
    private final com.ecobank.rccportal.service.CampaignFormAiService formAi;

    public CampaignController(CampaignService campaignService, com.ecobank.rccportal.service.CampaignResultsService results,
                              com.ecobank.rccportal.service.CampaignFormAiService formAi) {
        this.campaignService = campaignService;
        this.results = results;
        this.formAi = formAi;
    }

    // ───────────── Formulaires avancés (concepteur du Team Leader) ─────────────

    @PutMapping("/{id}")
    public CampaignResponse update(@PathVariable Integer id, @RequestBody CampaignRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.updateCampaign(requester, id, request);
    }

    @PostMapping("/{id}/duplicate")
    public CampaignResponse duplicate(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.duplicateCampaign(requester, id);
    }

    @PostMapping("/{id}/reopen")
    public void reopen(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        campaignService.reopenCampaign(requester, id);
    }

    /** Bibliothèque de modèles de formulaires (prêt, carte, NPS, digitalisation, épargne, KYC, assurance). */
    @GetMapping("/form/templates")
    public List<Map<String, Object>> templates(@AuthenticationPrincipal AuthenticatedUser requester) {
        campaignService.requireCanManage(requester);
        return com.ecobank.rccportal.service.CampaignFormTemplates.all().stream().map(t -> Map.<String, Object>of("code", t.code(), "title", t.title(),
                "description", t.description(), "icon", t.icon(),
                "form", com.ecobank.rccportal.service.CampaignFormEngine.normalize(t.form()))).toList();
    }

    /** Formulaire généré depuis une description libre (IA si configurée, sinon modèle le plus proche). */
    @PostMapping("/form/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> body, @AuthenticationPrincipal AuthenticatedUser requester) {
        campaignService.requireCanManage(requester);
        return formAi.generate(body.get("prompt"));
    }

    /** Contrôle d'un formulaire sans l'enregistrer : formulaire complété, ou message exact de ce qui ne va pas. */
    @PostMapping("/form/check")
    public com.fasterxml.jackson.databind.JsonNode check(@RequestBody com.fasterxml.jackson.databind.JsonNode form, @AuthenticationPrincipal AuthenticatedUser requester) {
        campaignService.requireCanManage(requester);
        return com.ecobank.rccportal.service.CampaignFormEngine.normalize(form);
    }

    /** Résultats : entonnoir, synthèse par question, scores, agents, activité quotidienne. */
    @GetMapping("/{id}/results")
    public Map<String, Object> results(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return results.results(requester, id);
    }

    @GetMapping("/{id}/results.csv")
    public org.springframework.http.ResponseEntity<byte[]> resultsCsv(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        byte[] body = results.exportCsv(requester, id);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"campagne-" + id + "-reponses.csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    @PostMapping
    public CampaignResponse create(@RequestBody CampaignRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.createCampaign(requester, request);
    }

    @GetMapping
    public List<CampaignResponse> list(@AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.listCampaigns(requester);
    }

    /** Campagnes actives visibles dans l'onglet "Campagne" de l'agent connecté (filtrées par
     *  sous-service Digital/Télévente — voir CampaignService.activeCampaignsForAgent). */
    @GetMapping("/active-for-me")
    public List<CampaignResponse> activeForMe(@AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.activeCampaignsForAgent(requester);
    }

    @PostMapping("/{id}/close")
    public void close(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        campaignService.closeCampaign(requester, id);
    }

    /** Étape 1 — prévisualisation avant import : lit les en-têtes du fichier fourni (n'importe
     *  quelle disposition de colonnes) et propose un mapping par défaut, sans rien enregistrer. */
    @PostMapping(value = "/{id}/import/preview", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public CampaignImportPreviewResponse previewImport(@PathVariable Integer id, @RequestParam("file") MultipartFile file,
                                                         @AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.previewImport(requester, id, file);
    }

    /** Étape 2 — confirmation de l'import avec le mapping choisi (ou ajusté) par l'utilisateur
     *  dans la modale de prévisualisation. "mapping" est optionnel pour compatibilité ascendante
     *  (appel direct sans passer par /preview) — dans ce cas le mapping est redéduit des en-têtes. */
    @PostMapping(value = "/{id}/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.ecobank.rccportal.dto.CampaignImportReport importContacts(@PathVariable Integer id, @RequestParam("file") MultipartFile file,
                                                @RequestPart(value = "mapping", required = false) CampaignImportMappingDto mapping,
                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.importContacts(requester, id, file, mapping);
    }

    /** Répartition équilibrée des contacts non assignés entre les agents choisis. */
    @PostMapping("/{id}/distribute")
    public Map<String, Object> distribute(@PathVariable Integer id, @RequestBody DistributeRequest body,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.distributeUnassigned(requester, id, body.agentUserIds(), Boolean.TRUE.equals(body.includeCalled()));
    }

    public record DistributeRequest(List<Long> agentUserIds, Boolean includeCalled) {}

    /** Auto-import — un agent charge directement sa propre liste d'appels, sans passer par un Team Leader. */
    @PostMapping(value = "/self-import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.ecobank.rccportal.dto.CampaignImportReport selfImportContacts(@RequestParam("file") MultipartFile file,
                                                     @RequestPart(value = "mapping", required = false) CampaignImportMappingDto mapping,
                                                     @AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.selfImportContacts(requester, file, mapping);
    }

    @GetMapping("/{id}/contacts")
    public List<CampaignContactResponse> contacts(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.contactsForCampaign(requester, id);
    }

    @PutMapping("/contacts/{contactId}/assign")
    public void assign(@PathVariable Integer contactId, @RequestBody Map<String, Long> body,
                        @AuthenticationPrincipal AuthenticatedUser requester) {
        campaignService.assignContact(requester, contactId, body.get("agentUserId"));
    }

    /** Liste d'appels de l'agent connecté — tous ses contacts, toutes campagnes actives confondues. */
    @GetMapping("/contacts/me")
    public List<CampaignContactResponse> myContacts(@AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.myContacts(requester);
    }

    /** Liste d'appels de l'agent connecté, restreinte à une campagne — onglet "Campagne". */
    @GetMapping("/{id}/contacts/me")
    public List<CampaignContactResponse> myContactsForCampaign(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.myContactsForCampaign(requester, id);
    }

    @PutMapping("/contacts/{contactId}/call-status")
    public CampaignContactResponse updateCallStatus(@PathVariable Integer contactId, @RequestBody UpdateCallStatusRequest request,
                                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        return campaignService.updateCallStatus(requester, contactId, request);
    }

    /** Relie le RDV créé (depuis un appel) au contact d'origine — pour que le Team Leader retrouve directement le lien. */
    @PutMapping("/contacts/{contactId}/link-appointment/{appointmentId}")
    public void linkAppointment(@PathVariable Integer contactId, @PathVariable Integer appointmentId) {
        campaignService.linkAppointment(contactId, appointmentId);
    }
}

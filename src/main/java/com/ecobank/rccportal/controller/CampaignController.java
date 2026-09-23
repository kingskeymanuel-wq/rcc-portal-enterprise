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

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
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
    public Map<String, Integer> importContacts(@PathVariable Integer id, @RequestParam("file") MultipartFile file,
                                                @RequestPart(value = "mapping", required = false) CampaignImportMappingDto mapping,
                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        int imported = campaignService.importContacts(requester, id, file, mapping);
        return Map.of("imported", imported);
    }

    /** Auto-import — un agent charge directement sa propre liste d'appels, sans passer par un Team Leader. */
    @PostMapping(value = "/self-import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Integer> selfImportContacts(@RequestParam("file") MultipartFile file,
                                                     @RequestPart(value = "mapping", required = false) CampaignImportMappingDto mapping,
                                                     @AuthenticationPrincipal AuthenticatedUser requester) {
        int imported = campaignService.selfImportContacts(requester, file, mapping);
        return Map.of("imported", imported);
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

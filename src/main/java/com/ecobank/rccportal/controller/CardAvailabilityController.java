package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.CardAvailabilityDtos.CardRequest;
import com.ecobank.rccportal.dto.CardAvailabilityDtos.CardResponse;
import com.ecobank.rccportal.dto.CardAvailabilityDtos.CellRequest;
import com.ecobank.rccportal.dto.CardAvailabilityDtos.CellResponse;
import com.ecobank.rccportal.dto.CardAvailabilityDtos.MatrixResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.CardAvailabilityService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * « Disponibilité des cartes » (voir CardAvailabilityService). Lecture pour tout agent
 * authentifié ; cochage et gestion des cartes réservés à QA/ADMIN.
 */
@RestController
@RequestMapping("/api/card-availability")
public class CardAvailabilityController {

    private final CardAvailabilityService service;
    private final com.ecobank.rccportal.service.CardAgencyStatusService agencyService;

    public CardAvailabilityController(CardAvailabilityService service,
                                      com.ecobank.rccportal.service.CardAgencyStatusService agencyService) {
        this.service = service;
        this.agencyService = agencyService;
    }

    // ── Point de disponibilité par agence (DATE | AGENCE | CARTE | CODE | TYPE DE CARTE) ──

    @GetMapping("/agencies")
    public com.ecobank.rccportal.dto.CardAgencyDtos.AgencyReport agencies(@RequestParam String country,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        return agencyService.report(country, date);
    }

    @PostMapping("/agencies")
    public com.ecobank.rccportal.dto.CardAgencyDtos.AgencyRow createAgencyRow(@Valid @RequestBody com.ecobank.rccportal.dto.CardAgencyDtos.AgencyRowRequest request,
                                                                             @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return agencyService.save(null, request, who(requester));
    }

    @PutMapping("/agencies/{id}")
    public com.ecobank.rccportal.dto.CardAgencyDtos.AgencyRow updateAgencyRow(@PathVariable Long id,
                                                                             @Valid @RequestBody com.ecobank.rccportal.dto.CardAgencyDtos.AgencyRowRequest request,
                                                                             @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return agencyService.save(id, request, who(requester));
    }

    @DeleteMapping("/agencies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAgencyRow(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        agencyService.delete(id);
    }

    @PostMapping("/agencies/paste")
    public com.ecobank.rccportal.dto.CardAgencyDtos.PasteResult pasteAgencies(@Valid @RequestBody com.ecobank.rccportal.dto.CardAgencyDtos.PasteRequest request,
                                                                             @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return agencyService.importPaste(request, who(requester));
    }

    private static String who(AuthenticatedUser requester) {
        return requester.name() != null ? requester.name() : requester.username();
    }

    @GetMapping
    public MatrixResponse matrix(@RequestParam String country) {
        return service.matrix(country);
    }

    @PutMapping("/cells")
    public CellResponse setCell(@Valid @RequestBody CellRequest request,
                                @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.setCell(request, requester.name() != null ? requester.name() : requester.username());
    }

    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse createCard(@Valid @RequestBody CardRequest request,
                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.createCard(request);
    }

    @PostMapping("/cards/standard")
    public List<CardResponse> addStandardCards(@RequestParam String country,
                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.addStandardCards(country);
    }

    @PutMapping("/cards/{id}")
    public CardResponse updateCard(@PathVariable Long id, @Valid @RequestBody CardRequest request,
                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.updateCard(id, request);
    }

    @DeleteMapping("/cards/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCard(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        service.deleteCard(id);
    }

    /** Même règle que BankBranchController / RccPoleController. */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Seuls QA et Administration peuvent modifier la disponibilité des cartes.");
        }
    }
}

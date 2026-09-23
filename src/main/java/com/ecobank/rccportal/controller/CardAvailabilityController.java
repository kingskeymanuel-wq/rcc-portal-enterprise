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

    public CardAvailabilityController(CardAvailabilityService service) {
        this.service = service;
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

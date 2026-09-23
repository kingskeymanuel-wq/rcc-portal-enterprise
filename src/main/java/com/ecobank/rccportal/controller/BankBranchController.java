package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.BankBranchCityResponse;
import com.ecobank.rccportal.dto.BankBranchRequest;
import com.ecobank.rccportal.dto.BankBranchResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.BankBranchService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * "Carte des banques" — voir BankBranchService pour le détail du flux
 * (pays → villes → agences). Lecture ouverte à tout agent authentifié ;
 * écriture réservée à QA/ADMIN (même règle que RccPoleController).
 */
@RestController
@RequestMapping("/api/bank-branches")
public class BankBranchController {

    private final BankBranchService branchService;

    public BankBranchController(BankBranchService branchService) {
        this.branchService = branchService;
    }

    @GetMapping
    public List<BankBranchResponse> list(@RequestParam String country,
                                          @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        if (includeInactive) {
            requireQaOrAdmin(requester);
        }
        return branchService.listByCountry(country, includeInactive);
    }

    @GetMapping("/cities")
    public List<BankBranchCityResponse> listCities(@RequestParam String country) {
        return branchService.listCitiesByCountry(country);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BankBranchResponse create(@Valid @RequestBody BankBranchRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return branchService.create(request);
    }

    @PutMapping("/{id}")
    public BankBranchResponse update(@PathVariable Long id, @Valid @RequestBody BankBranchRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return branchService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        branchService.delete(id);
    }

    /** Même règle que RccPoleController.requireQaOrAdmin. */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Seuls QA et Administration peuvent modifier les agences.");
        }
    }
}

package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.AllowedTabsResponse;
import com.ecobank.rccportal.dto.TabPermissionRequest;
import com.ecobank.rccportal.dto.TabPermissionResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.TabPermissionService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tab-permissions")
public class TabPermissionController {

    private final TabPermissionService tabPermissionService;

    public TabPermissionController(TabPermissionService tabPermissionService) {
        this.tabPermissionService = tabPermissionService;
    }

    /** Onglets interdits pour l'utilisateur courant — tout le reste est autorisé par défaut. */
    @GetMapping("/me")
    public AllowedTabsResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return tabPermissionService.resolveForUser(user);
    }

    @GetMapping
    public List<TabPermissionResponse> listAll(@AuthenticationPrincipal AuthenticatedUser user) {
        requireAdmin(user);
        return tabPermissionService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TabPermissionResponse upsert(@Valid @RequestBody TabPermissionRequest request,
                                         @AuthenticationPrincipal AuthenticatedUser user) {
        requireAdmin(user);
        return tabPermissionService.upsert(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser user) {
        requireAdmin(user);
        tabPermissionService.remove(id);
    }

    private void requireAdmin(AuthenticatedUser user) {
        if (!"admin".equalsIgnoreCase(user.role())) {
            throw ApiException.forbidden("Only an administrator can manage tab permissions.");
        }
    }
}

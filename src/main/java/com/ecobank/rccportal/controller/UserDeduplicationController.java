package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.DuplicateUserGroupResponse;
import com.ecobank.rccportal.dto.UserMergeResultResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.UserDeduplicationService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Nettoyage des comptes USERS en doublon (même USERNAME) — réservé Admin, opération
 * irréversible une fois exécutée (voir UserDeduplicationService pour la logique de fusion).
 * Flux attendu côté écran : GET /preview (aucun effet, montre ce qui SERAIT fait) → l'admin
 * valide manuellement chaque groupe → POST /merge par username → une fois tout fusionné,
 * POST /lock-username pour empêcher toute récidive.
 */
@RestController
@RequestMapping("/api/admin/user-deduplication")
public class UserDeduplicationController {

    private final UserDeduplicationService service;

    public UserDeduplicationController(UserDeduplicationService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    public List<DuplicateUserGroupResponse> preview(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return service.preview();
    }

    @PostMapping("/merge")
    public UserMergeResultResponse merge(@RequestBody Map<String, String> body,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        String username = body.get("username");
        if (username == null || username.isBlank()) {
            throw ApiException.badRequest("username is required.");
        }
        return service.mergeByUsername(username);
    }

    @PostMapping("/lock-username")
    public Map<String, String> lockUsername(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        service.addUniqueUsernameConstraint();
        return Map.of("status", "locked");
    }

    private void requireAdmin(AuthenticatedUser requester) {
        if (requester == null || !"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Seul un administrateur peut nettoyer les comptes en doublon.");
        }
    }
}

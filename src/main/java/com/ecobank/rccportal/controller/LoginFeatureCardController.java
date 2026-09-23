package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.LoginFeatureCardRequest;
import com.ecobank.rccportal.dto.LoginFeatureCardResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.LoginFeatureCardService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/login-feature-cards")
public class LoginFeatureCardController {

    private final LoginFeatureCardService service;

    public LoginFeatureCardController(LoginFeatureCardService service) {
        this.service = service;
    }

    /** Public — nécessaire pour la page de connexion, avant authentification. */
    @GetMapping("/public")
    public List<LoginFeatureCardResponse> listPublic() {
        return service.listActive();
    }

    @GetMapping
    public List<LoginFeatureCardResponse> listAll(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.listAllForAdmin();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LoginFeatureCardResponse create(@RequestBody LoginFeatureCardRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.create(request);
    }

    @PutMapping("/{id}")
    public LoginFeatureCardResponse update(@PathVariable Integer id, @RequestBody LoginFeatureCardRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        service.delete(id);
    }

    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Seule la Quality Assurance ou un administrateur peut gérer la page de connexion.");
        }
    }
}

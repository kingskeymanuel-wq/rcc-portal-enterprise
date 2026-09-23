package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.RequestTemplateRequest;
import com.ecobank.rccportal.dto.RequestTemplateResponse;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.RequestTemplateService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow/templates")
public class RequestTemplateController {

    private final RequestTemplateService service;
    private final UserRepository userRepository;

    public RequestTemplateController(RequestTemplateService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    /** Tout utilisateur authentifié peut lister les modèles actifs pour préremplir sa demande. */
    @GetMapping
    public List<RequestTemplateResponse> list() {
        return service.listActive();
    }

    @GetMapping("/all")
    public List<RequestTemplateResponse> listAll(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestTemplateResponse create(@RequestBody RequestTemplateRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.create(request, resolveUserId(requester));
    }

    @PutMapping("/{id}")
    public RequestTemplateResponse update(@PathVariable Integer id, @RequestBody RequestTemplateRequest request,
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
            throw ApiException.forbidden("Seule la Quality Assurance ou un administrateur peut gérer les modèles de demande.");
        }
    }

    private Long resolveUserId(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) return null;
        return userRepository.findFirstByUsernameIgnoreCase(requester.username()).map(User::getId).orElse(null);
    }
}

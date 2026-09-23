package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.WordTermRequest;
import com.ecobank.rccportal.dto.WordTermResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.WordTermService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/word-terms")
public class WordTermController {

    private final WordTermService service;

    public WordTermController(WordTermService service) {
        this.service = service;
    }

    @GetMapping("/draw")
    public List<WordTermResponse> draw(@RequestParam(defaultValue = "1") int count) {
        return service.drawRandom(count);
    }

    @GetMapping
    public List<WordTermResponse> list(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WordTermResponse create(@RequestBody WordTermRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.create(request);
    }

    @PutMapping("/{id}")
    public WordTermResponse update(@PathVariable Integer id, @RequestBody WordTermRequest request,
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
            throw ApiException.forbidden("Seule la Quality Assurance ou un administrateur peut gérer le vocabulaire.");
        }
    }
}

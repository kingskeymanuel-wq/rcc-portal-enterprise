package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.LoginFeatureCardRequest;
import com.ecobank.rccportal.dto.LoginFeatureCardResponse;
import com.ecobank.rccportal.model.LoginFeatureCard;
import com.ecobank.rccportal.repository.LoginFeatureCardRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class LoginFeatureCardService {

    private final LoginFeatureCardRepository repository;

    public LoginFeatureCardService(LoginFeatureCardRepository repository) {
        this.repository = repository;
    }

    /** Public (page de connexion, avant authentification) — ne renvoie jamais d'erreur brute. */
    @Transactional(readOnly = true)
    public List<LoginFeatureCardResponse> listActive() {
        try {
            return repository.findByActiveTrueOrderBySortOrderAsc().stream().map(this::toResponse).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public List<LoginFeatureCardResponse> listAllForAdmin() {
        try {
            return repository.findAllByOrderBySortOrderAsc().stream().map(this::toResponse).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional
    public LoginFeatureCardResponse create(LoginFeatureCardRequest request) {
        validate(request);
        LoginFeatureCard card = LoginFeatureCard.builder()
                .icon(request.icon().trim())
                .title(request.title().trim())
                .subtitle(request.subtitle())
                .sortOrder(request.sortOrder() == null ? (int) repository.count() : request.sortOrder())
                .active(request.active() == null || request.active())
                .build();
        return toResponse(repository.save(card));
    }

    @Transactional
    public LoginFeatureCardResponse update(Integer id, LoginFeatureCardRequest request) {
        validate(request);
        LoginFeatureCard card = repository.findById(id).orElseThrow(() -> ApiException.notFound("Carte introuvable."));
        card.setIcon(request.icon().trim());
        card.setTitle(request.title().trim());
        card.setSubtitle(request.subtitle());
        if (request.sortOrder() != null) card.setSortOrder(request.sortOrder());
        if (request.active() != null) card.setActive(request.active());
        return toResponse(repository.save(card));
    }

    @Transactional
    public void delete(Integer id) {
        LoginFeatureCard card = repository.findById(id).orElseThrow(() -> ApiException.notFound("Carte introuvable."));
        repository.delete(card);
    }

    private void validate(LoginFeatureCardRequest r) {
        if (r.icon() == null || r.icon().isBlank()) throw ApiException.badRequest("L'icône est obligatoire.");
        if (r.title() == null || r.title().isBlank()) throw ApiException.badRequest("Le titre est obligatoire.");
    }

    private LoginFeatureCardResponse toResponse(LoginFeatureCard c) {
        return new LoginFeatureCardResponse(c.getCardId(), c.getIcon(), c.getTitle(), c.getSubtitle(), c.getSortOrder(), c.getActive());
    }
}

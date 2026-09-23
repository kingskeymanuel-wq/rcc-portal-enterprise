package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.RequestTemplateRequest;
import com.ecobank.rccportal.dto.RequestTemplateResponse;
import com.ecobank.rccportal.model.RequestTemplate;
import com.ecobank.rccportal.repository.RequestTemplateRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RequestTemplateService {

    private static final Set<String> ALLOWED_TYPES = Set.of("LEAVE", "PROCEDURE_CHANGE", "ACCESS");

    private final RequestTemplateRepository repository;

    public RequestTemplateService(RequestTemplateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<RequestTemplateResponse> listActive() {
        return repository.findByActiveTrueOrderByNameAsc().stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RequestTemplateResponse> listAll() {
        return repository.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public RequestTemplateResponse create(RequestTemplateRequest request, Long requesterId) {
        validate(request);
        RequestTemplate t = RequestTemplate.builder()
                .name(request.name().trim())
                .type(request.type())
                .defaultTitle(request.defaultTitle().trim())
                .defaultDetails(request.defaultDetails())
                .defaultAssignedTeam(request.defaultAssignedTeam())
                .active(request.active() == null || request.active())
                .createdByUserId(requesterId)
                .build();
        return toResponse(repository.save(t));
    }

    @Transactional
    public RequestTemplateResponse update(Integer id, RequestTemplateRequest request) {
        validate(request);
        RequestTemplate t = repository.findById(id).orElseThrow(() -> ApiException.notFound("Modèle introuvable."));
        t.setName(request.name().trim());
        t.setType(request.type());
        t.setDefaultTitle(request.defaultTitle().trim());
        t.setDefaultDetails(request.defaultDetails());
        t.setDefaultAssignedTeam(request.defaultAssignedTeam());
        if (request.active() != null) t.setActive(request.active());
        return toResponse(repository.save(t));
    }

    @Transactional
    public void delete(Integer id) {
        RequestTemplate t = repository.findById(id).orElseThrow(() -> ApiException.notFound("Modèle introuvable."));
        repository.delete(t);
    }

    private void validate(RequestTemplateRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Le nom du modèle est obligatoire.");
        if (r.type() == null || !ALLOWED_TYPES.contains(r.type())) throw ApiException.badRequest("Type invalide.");
        if (r.defaultTitle() == null || r.defaultTitle().isBlank()) throw ApiException.badRequest("Le titre par défaut est obligatoire.");
    }

    private RequestTemplateResponse toResponse(RequestTemplate t) {
        return new RequestTemplateResponse(t.getTemplateId(), t.getName(), t.getType(), t.getDefaultTitle(),
                t.getDefaultDetails(), t.getDefaultAssignedTeam(), t.getActive());
    }
}

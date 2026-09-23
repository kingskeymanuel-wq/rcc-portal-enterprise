package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.SalesJourneyRequest;
import com.ecobank.rccportal.dto.SalesJourneyResponse;
import com.ecobank.rccportal.model.SalesJourney;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.SalesJourneyRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Parcours de vente interactifs administrables — remplace l'ancien fichier statique
 * sales-journeys-data.js. Lecture ouverte à tout agent (voir outbound-dashboard.js) ;
 * écriture réservée à QA/Admin ou au Team Leader de l'équipe Outbound.
 */
@Service
public class SalesJourneyService {

    private final SalesJourneyRepository repository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public SalesJourneyService(SalesJourneyRepository repository, UserRepository userRepository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<SalesJourneyResponse> listActive() {
        return repository.findByActiveTrueOrderBySortOrderAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<SalesJourneyResponse> listAllForAdmin(AuthenticatedUser requester) {
        requireCanManage(requester);
        return repository.findAllByOrderBySortOrderAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public SalesJourneyResponse create(AuthenticatedUser requester, SalesJourneyRequest request) {
        requireCanManage(requester);
        validate(request);
        if (request.journeyKey() != null && repository.findByJourneyKeyIgnoreCase(request.journeyKey().trim()).isPresent()) {
            throw ApiException.badRequest("Un parcours existe déjà avec cette clé.");
        }
        SalesJourney journey = SalesJourney.builder()
                .journeyKey(slugOrGenerate(request.journeyKey(), request.title()))
                .title(request.title().trim())
                .icon(blankToDefault(request.icon(), "bi-briefcase-fill"))
                .colorFrom(blankToDefault(request.colorFrom(), "#0057B8"))
                .colorTo(blankToDefault(request.colorTo(), "#00A651"))
                .pitch(request.pitch())
                .stepsJson(serializeSteps(request.steps()))
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : (int) repository.count())
                .active(request.active() == null || request.active())
                .build();
        return toResponse(repository.save(journey));
    }

    @Transactional
    public SalesJourneyResponse update(AuthenticatedUser requester, Integer journeyId, SalesJourneyRequest request) {
        requireCanManage(requester);
        validate(request);
        SalesJourney journey = repository.findById(journeyId)
                .orElseThrow(() -> ApiException.notFound("Parcours introuvable."));
        journey.setTitle(request.title().trim());
        journey.setIcon(blankToDefault(request.icon(), journey.getIcon()));
        journey.setColorFrom(blankToDefault(request.colorFrom(), journey.getColorFrom()));
        journey.setColorTo(blankToDefault(request.colorTo(), journey.getColorTo()));
        journey.setPitch(request.pitch());
        journey.setStepsJson(serializeSteps(request.steps()));
        if (request.sortOrder() != null) journey.setSortOrder(request.sortOrder());
        if (request.active() != null) journey.setActive(request.active());
        journey.setUpdatedAt(LocalDateTime.now());
        return toResponse(repository.save(journey));
    }

    @Transactional
    public void delete(AuthenticatedUser requester, Integer journeyId) {
        requireCanManage(requester);
        SalesJourney journey = repository.findById(journeyId)
                .orElseThrow(() -> ApiException.notFound("Parcours introuvable."));
        repository.delete(journey);
    }

    // ═══════════════════════════════════════════════════════════════════

    private void requireCanManage(AuthenticatedUser requester) {
        if (requester == null) throw ApiException.unauthorized("Utilisateur non authentifié.");
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (isAdmin || isQa) return;
        if ("team_leader".equalsIgnoreCase(requester.role())) {
            User u = userRepository.findFirstByUsernameIgnoreCase(requester.username()).orElse(null);
            if (u != null && "OUTBOUND".equalsIgnoreCase(u.getLedTeam())) return;
        }
        throw ApiException.forbidden("Réservé à QA/Admin ou au Team Leader de l'équipe Outbound.");
    }

    private void validate(SalesJourneyRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            throw ApiException.badRequest("Le titre est obligatoire.");
        }
        if (request.steps() == null || request.steps().isEmpty()) {
            throw ApiException.badRequest("Le parcours doit avoir au moins une étape.");
        }
        for (SalesJourneyResponse.StepDto step : request.steps()) {
            if (step.title() == null || step.title().isBlank()) {
                throw ApiException.badRequest("Chaque étape doit avoir un titre.");
            }
            if (step.content() == null || step.content().isEmpty()) {
                throw ApiException.badRequest("Chaque étape doit contenir au moins une ligne.");
            }
        }
    }

    private String serializeSteps(List<SalesJourneyResponse.StepDto> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception e) {
            throw ApiException.badRequest("Format des étapes invalide.");
        }
    }

    private List<SalesJourneyResponse.StepDto> deserializeSteps(String json) {
        try {
            return List.of(objectMapper.readValue(json, SalesJourneyResponse.StepDto[].class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String slugOrGenerate(String key, String title) {
        String base = (key != null && !key.isBlank()) ? key : title;
        return base.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private SalesJourneyResponse toResponse(SalesJourney j) {
        return new SalesJourneyResponse(j.getJourneyId(), j.getJourneyKey(), j.getTitle(), j.getIcon(),
                j.getColorFrom(), j.getColorTo(), j.getPitch(), deserializeSteps(j.getStepsJson()),
                j.getSortOrder(), j.getActive());
    }
}

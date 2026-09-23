package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.KpiEventCountResponse;
import com.ecobank.rccportal.dto.KpiEventResponse;
import com.ecobank.rccportal.model.KpiEvent;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.KpiEventRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Événements auto-tracés (remplace eco_kpidb : proc_add, proc_view, ...).
 * `record(...)` est pensé pour être appelé DEPUIS d'autres services (ex.
 * ProcedureService lors d'une création/consultation), pas exposé comme route
 * publique — un utilisateur ne "poste" pas ses propres statistiques.
 */
@Service
public class KpiEventService {

    private final KpiEventRepository kpiEventRepository;
    private final UserRepository userRepository;

    public KpiEventService(KpiEventRepository kpiEventRepository, UserRepository userRepository) {
        this.kpiEventRepository = kpiEventRepository;
        this.userRepository = userRepository;
    }

    /** username peut être null (événement système, sans utilisateur identifié). */
    @Transactional
    public void record(String username, String eventType, String eventKey) {
        User user = username != null ? userRepository.findFirstByUsernameIgnoreCase(username).orElse(null) : null;
        kpiEventRepository.save(KpiEvent.builder()
                .user(user).eventType(eventType).eventKey(eventKey).occurredAt(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<KpiEventResponse> listForUser(String username, LocalDateTime from, LocalDateTime to) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("Unknown user."));
        return kpiEventRepository.findByUserAndOccurredAtBetweenOrderByOccurredAtDesc(user, from, to).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<KpiEventCountResponse> countByTypeForUser(String username, LocalDateTime from, LocalDateTime to) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("Unknown user."));
        return kpiEventRepository.countByEventTypeForUser(user, from, to).stream()
                .map(row -> new KpiEventCountResponse(row.getEventType(), row.getTotal()))
                .toList();
    }

    private KpiEventResponse toResponse(KpiEvent e) {
        return new KpiEventResponse(e.getKpiEventId(), e.getUser() != null ? e.getUser().getUsername() : null,
                e.getEventType(), e.getEventKey(), e.getOccurredAt());
    }
}

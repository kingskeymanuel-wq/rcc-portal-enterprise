package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.SlaRuleRequest;
import com.ecobank.rccportal.dto.SlaRuleResponse;
import com.ecobank.rccportal.model.SlaRule;
import com.ecobank.rccportal.repository.SlaRuleRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gestion des règles SLA RCC (délais de traitement communiqués au client). Lecture
 * ouverte à tout agent authentifié ; écriture réservée aux admins (voir SlaRuleController).
 */
@Service
public class SlaRuleService {

    private final SlaRuleRepository repository;

    public SlaRuleService(SlaRuleRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<SlaRuleResponse> listActive() {
        return repository.findByIsActiveTrueOrderBySortOrderAscMotifAsc().stream()
                .map(SlaRuleResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SlaRuleResponse> listAll() {
        return repository.findAllByOrderBySortOrderAscMotifAsc().stream()
                .map(SlaRuleResponse::from)
                .toList();
    }

    @Transactional
    public SlaRuleResponse create(SlaRuleRequest request) {
        SlaRule rule = new SlaRule();
        apply(rule, request);
        return SlaRuleResponse.from(repository.save(rule));
    }

    @Transactional
    public SlaRuleResponse update(Integer id, SlaRuleRequest request) {
        SlaRule rule = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Règle SLA introuvable (id=" + id + ")."));
        apply(rule, request);
        return SlaRuleResponse.from(repository.save(rule));
    }

    @Transactional
    public void delete(Integer id) {
        if (!repository.existsById(id)) {
            throw ApiException.notFound("Règle SLA introuvable (id=" + id + ").");
        }
        repository.deleteById(id);
    }

    private void apply(SlaRule rule, SlaRuleRequest request) {
        rule.setMotif(request.motif());
        rule.setCategory(request.category());
        rule.setLevel(request.level());
        rule.setSlaHours(request.slaHours());
        rule.setSlaLabel(request.slaLabel());
        rule.setDestinationService(request.destinationService());
        rule.setPriority(request.priority());
        rule.setAutoEscalation(request.autoEscalation() != null && request.autoEscalation());
        rule.setNotes(request.notes());
        rule.setIsActive(request.isActive() == null || request.isActive());
        rule.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
    }
}

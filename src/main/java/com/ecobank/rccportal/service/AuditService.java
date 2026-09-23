package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.LoginAuditResponse;
import com.ecobank.rccportal.model.LoginAudit;
import com.ecobank.rccportal.repository.LoginAuditRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Journal des événements de connexion (succès, échec, verrouillage, réinitialisation). */
@Service
public class AuditService {

    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_LIMIT = 100;

    private final LoginAuditRepository loginAuditRepository;

    public AuditService(LoginAuditRepository loginAuditRepository) {
        this.loginAuditRepository = loginAuditRepository;
    }

    @Transactional(readOnly = true)
    public List<LoginAuditResponse> listRecent(Integer limit) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return loginAuditRepository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, effectiveLimit))
                .stream().map(this::toResponse).toList();
    }

    private LoginAuditResponse toResponse(LoginAudit e) {
        return new LoginAuditResponse(
                e.getLoginAuditId(),
                e.getUser() != null ? e.getUser().getUsername() : null,
                e.getUser() != null ? e.getUser().getName() : null,
                e.getEventType(),
                e.getOccurredAt(),
                e.getPasswordAgeDays(),
                e.getIpAddress()
        );
    }
}

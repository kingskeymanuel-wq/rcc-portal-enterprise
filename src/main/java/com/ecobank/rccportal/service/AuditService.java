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
    private final com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServices;

    public AuditService(LoginAuditRepository loginAuditRepository,
                        com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServices) {
        this.loginAuditRepository = loginAuditRepository;
        this.userServices = userServices;
    }

    @Transactional(readOnly = true)
    public List<LoginAuditResponse> listRecent(Integer limit) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        java.util.Map<Long, String> teams = new java.util.HashMap<>();
        return loginAuditRepository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, effectiveLimit))
                .stream().map(e -> toResponse(e, teams)).toList();
    }

    /** Équipe affichée dans le journal : canal digital (Tchat, Rafiki), équipe, sinon profil du service. */
    static String teamOf(String activity, java.util.List<String> codes) {
        String channel = com.ecobank.rccportal.util.TeamClassifier.channel(activity, codes);
        if (channel != null) return channel;
        var team = com.ecobank.rccportal.util.TeamClassifier.classify(activity, codes);
        if (team != com.ecobank.rccportal.util.TeamClassifier.Team.OTHER) return team.name();
        for (String c : codes) {
            String u = c.toUpperCase(java.util.Locale.ROOT);
            if (u.equals("RH")) return "RH";
            if (u.contains("QA") || u.contains("QUALITY")) return "QA";
            if (u.startsWith("SUPERVISEUR")) return "SUPERVISION";
            if (u.startsWith("AGENCE")) return "AGENCE";
            if (u.equals("FORMATEUR")) return "FORMATION";
        }
        return "AUTRE";
    }

    private LoginAuditResponse toResponse(LoginAudit e, java.util.Map<Long, String> teams) {
        var u = e.getUser();
        String team = u == null || u.getId() == null ? null : teams.computeIfAbsent(u.getId(), id -> {
            try {
                java.util.List<String> codes = userServices.findServicesByUserId(id).stream()
                        .filter(a -> a.getService() != null && a.getService().getCode() != null)
                        .map(a -> a.getService().getCode()).toList();
                return teamOf(u.getActivity(), codes);
            } catch (RuntimeException ex) {
                return "AUTRE";
            }
        });
        return new LoginAuditResponse(
                e.getLoginAuditId(),
                e.getUser() != null ? e.getUser().getUsername() : null,
                e.getUser() != null ? e.getUser().getName() : null,
                e.getEventType(),
                e.getOccurredAt(),
                e.getPasswordAgeDays(),
                e.getIpAddress(),
                u == null ? null : HrOrganizationService.country(u.getAffiliateBranch()),
                team
        );
    }
}

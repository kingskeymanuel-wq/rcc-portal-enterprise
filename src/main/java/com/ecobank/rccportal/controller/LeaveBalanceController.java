package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.LeaveBalanceResponse;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.LeaveBalanceService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Year;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/leave-balance")
public class LeaveBalanceController {

    private final LeaveBalanceService service;
    private final UserRepository userRepository;

    public LeaveBalanceController(LeaveBalanceService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    /** Mon propre solde — ouvert à tout utilisateur authentifié. */
    @GetMapping("/me")
    public LeaveBalanceResponse mine(@RequestParam(required = false) Integer year,
                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        Long userId = resolveUserId(requester);
        if (userId == null) throw ApiException.unauthorized("Utilisateur non authentifié.");
        return service.getBalance(userId, year != null ? year : Year.now().getValue());
    }

    /** Tous les soldes — RH/QA/Admin voient tout ; un Team Leader ne voit que les soldes de SA
     *  propre équipe (voir User.ledTeam), jamais celle des autres. */
    @GetMapping
    public List<LeaveBalanceResponse> all(@RequestParam(required = false) Integer year,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        return requireReviewer(requester, year != null ? year : Year.now().getValue());
    }

    /**
     * Résout la liste de soldes visibles par l'appelant selon son profil : RH/QA/Admin voient
     * tout ; un Team Leader est restreint aux membres de l'équipe qu'il dirige (User.ledTeam,
     * même champ que celui utilisé côté Suivi de shift — voir ShiftController) ; tout autre
     * profil est refusé.
     */
    private List<LeaveBalanceResponse> requireReviewer(AuthenticatedUser requester, int year) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isRh = requester != null && "rh".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (isAdmin || isRh || isQa) return service.getAllBalances(year);

        boolean isTeamLeader = requester != null && "team_leader".equalsIgnoreCase(requester.role());
        if (isTeamLeader) {
            String ledTeam = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                    .map(User::getLedTeam)
                    .orElse(null);
            if (ledTeam == null || ledTeam.isBlank()) return List.of();
            return service.getAllBalances(year).stream()
                    .filter(b -> ledTeam.equalsIgnoreCase(b.team()))
                    .toList();
        }

        throw ApiException.forbidden("Seuls RH, la Quality Assurance, un Team Leader ou un administrateur peuvent consulter les soldes de congés d'une équipe.");
    }

    @PostMapping("/{userId}")
    public LeaveBalanceResponse setAllocated(@PathVariable Long userId, @RequestBody Map<String, Integer> body,
                                              @AuthenticationPrincipal AuthenticatedUser requester) {
        requireHrQaOrAdmin(requester);
        Integer year = body.get("year") != null ? body.get("year") : Year.now().getValue();
        Integer allocatedDays = body.get("allocatedDays");
        if (allocatedDays == null) throw ApiException.badRequest("allocatedDays is required.");
        return service.setAllocated(userId, year, allocatedDays, resolveUserId(requester));
    }

    private void requireHrQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isRh = requester != null && "rh".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isRh && !isQa) {
            throw ApiException.forbidden("Seuls RH, la Quality Assurance ou un administrateur peuvent gérer les soldes de congés.");
        }
    }

    private Long resolveUserId(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) return null;
        return userRepository.findFirstByUsernameIgnoreCase(requester.username()).map(User::getId).orElse(null);
    }
}

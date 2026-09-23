package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.ProductivityAgentResponse;
import com.ecobank.rccportal.dto.ProductivityRankingEntry;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.ProductivityService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/productivity")
public class ProductivityController {

    private final ProductivityService productivityService;
    private final com.ecobank.rccportal.repository.UserRepository userRepository;

    public ProductivityController(ProductivityService productivityService,
                                  com.ecobank.rccportal.repository.UserRepository userRepository) {
        this.productivityService = productivityService;
        this.userRepository = userRepository;
    }

    /** Historique de productivité — l'agent voit le sien, QA/RH/Superviseur/Admin/Team Leader voient n'importe quel agent. */
    @GetMapping("/agent/{username}")
    public ProductivityAgentResponse forAgent(@PathVariable String username, @RequestParam(required = false) String metricCode,
                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        if (!username.equalsIgnoreCase(requester.username())) requireReviewer(requester);
        return productivityService.forAgent(username, metricCode);
    }

    /** Mon propre historique de productivité — raccourci pour l'agent connecté. */
    @GetMapping("/me")
    public ProductivityAgentResponse myProductivity(@RequestParam(required = false) String metricCode,
                                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        return productivityService.forAgent(requester.username(), metricCode);
    }

    /** Classement de l'équipe — QA/RH/Superviseur/Admin voient toutes les équipes, Team Leader la sienne uniquement. */
    @GetMapping("/ranking")
    public List<ProductivityRankingEntry> ranking(@RequestParam(required = false) String month,
                                                    @RequestParam(required = false) String team,
                                                    @RequestParam(required = false) String metricCode,
                                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        YearMonth targetMonth = (month != null && !month.isBlank()) ? YearMonth.parse(month) : YearMonth.now();
        boolean isTeamLeader = "team_leader".equalsIgnoreCase(requester.role());
        String effectiveTeam = team;
        if (isTeamLeader) {
            // Un Team Leader ne peut jamais demander l'équipe d'un autre — la sienne est imposée.
            effectiveTeam = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                    .map(com.ecobank.rccportal.model.User::getLedTeam).orElse(null);
            if (effectiveTeam == null) return List.of();
        } else {
            requireReviewer(requester);
        }
        return productivityService.ranking(targetMonth, effectiveTeam, metricCode);
    }

    private void requireReviewer(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isRh && !isSupervisor && !isQa) {
            throw ApiException.forbidden("Réservé à QA, RH, Superviseur, ou administrateur.");
        }
    }
}

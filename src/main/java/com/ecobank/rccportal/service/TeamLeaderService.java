package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AttendanceRecordResponse;
import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.dto.QualityEvaluationResponse;
import com.ecobank.rccportal.dto.UserDirectoryResponse;
import com.ecobank.rccportal.dto.UserResponse;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Portail Team Leader — chaque Team Leader ne voit que SON équipe (User.ledTeam, affecté
 * manuellement par l'admin), classifiée via TeamClassifier à partir du champ activity de
 * chaque agent (très hétérogène en base, d'où la classification plutôt qu'un match exact).
 */
@Service
public class TeamLeaderService {

    private final UserRepository userRepository;
    private final ReportingService reportingService;
    private final AttendanceService attendanceService;
    private final QualityEvaluationService qualityEvaluationService;
    private final UserService userService;
    private final com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository;

    public TeamLeaderService(UserRepository userRepository, ReportingService reportingService,
                              AttendanceService attendanceService, QualityEvaluationService qualityEvaluationService,
                              UserService userService, com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository) {
        this.userRepository = userRepository;
        this.reportingService = reportingService;
        this.attendanceService = attendanceService;
        this.qualityEvaluationService = qualityEvaluationService;
        this.userService = userService;
        this.userRoleRepository = userRoleRepository;
    }

    /** Valeur canonique de User.activity à écrire pour qu'un agent soit reconnu dans telle
     *  équipe par TeamClassifier.classify() — même mapping que
     *  AdministrationService.SERVICE_CODE_TO_ACTIVITY, ici indexé par Team plutôt que par
     *  code service. OTHER n'y figure pas : on ne "range" jamais quelqu'un dans "Non classée",
     *  cette valeur ne s'obtient qu'en vidant le champ (voir removeMember()).
     */
    private static final java.util.Map<TeamClassifier.Team, String> TEAM_TO_ACTIVITY = java.util.Map.of(
            TeamClassifier.Team.INBOUND_VOICE, "INBOUND VOICE",
            TeamClassifier.Team.INBOUND_MAIL, "INBOUND MAIL",
            TeamClassifier.Team.CIB, "CIB",
            TeamClassifier.Team.OUTBOUND, "OUTBOUND"
    );

    /** true si ce compte porte un rôle de management/support (pas un agent opérationnel) —
     *  jamais éligible pour être ajouté à une équipe Team Leader. Même logique que
     *  UserService.teamStatus() pour repérer un "simple agent". */
    private boolean isManagementAccount(Long userId) {
        return userRoleRepository.findRolesByUserId(userId).stream()
                .anyMatch(ur -> {
                    String n = ur.getRole() != null ? ur.getRole().getName() : null;
                    return n != null && !"AGENT".equalsIgnoreCase(n.trim());
                });
    }

    /**
     * Recherche des agents éligibles à rejoindre mon équipe (pas déjà dedans, pas un compte de
     * management) — pour le bouton "Ajouter un agent" de l'onglet Membres.
     */
    @Transactional(readOnly = true)
    public List<UserDirectoryResponse> searchAddableAgents(AuthenticatedUser requester, String query) {
        TeamClassifier.Team team = requireLedTeam(requester);
        String q = query == null ? "" : query.trim().toLowerCase();
        return userRepository.findAll().stream()
                .filter(u -> TeamClassifier.classify(u.getActivity()) != team)
                .filter(u -> !isManagementAccount(u.getId()))
                .filter(u -> q.isEmpty()
                        || (u.getName() != null && u.getName().toLowerCase().contains(q))
                        || (u.getUsername() != null && u.getUsername().toLowerCase().contains(q)))
                .map(this::toDirectory)
                .sorted((a, b) -> String.valueOf(a.fullName()).compareToIgnoreCase(String.valueOf(b.fullName())))
                .limit(20)
                .toList();
    }

    /** Ajoute un agent existant à mon équipe — positionne son équipe (activity) sur la mienne. */
    @Transactional
    public void addMember(AuthenticatedUser requester, Long userId) {
        TeamClassifier.Team team = requireLedTeam(requester);
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Unknown user."));
        if (isManagementAccount(userId)) {
            throw ApiException.badRequest("Ce compte porte un rôle de management — il ne peut pas être ajouté à une équipe opérationnelle.");
        }
        user.setActivity(TEAM_TO_ACTIVITY.get(team));
        userRepository.save(user);
    }

    /**
     * Retire un agent de mon équipe — vide son champ équipe (activity), donc il n'apparaît
     * plus classé nulle part tant qu'un admin/Team Leader ne le range pas ailleurs. Si
     * resigned=true (démission), désactive aussi le compte — visible immédiatement partout
     * ailleurs sur le site (RH, Superviseur, Reporting) puisque c'est le même champ
     * User.accountEnabled déjà utilisé partout, jamais une copie séparée à resynchroniser.
     */
    @Transactional
    public void removeMember(AuthenticatedUser requester, Long userId, boolean resigned) {
        TeamClassifier.Team team = requireLedTeam(requester);
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Unknown user."));
        if (TeamClassifier.classify(user.getActivity()) != team) {
            throw ApiException.forbidden("Cet agent ne fait pas partie de votre équipe.");
        }
        user.setActivity(null);
        if (resigned) {
            user.setAccountEnabled(false);
        }
        userRepository.save(user);
    }

    /** Équipe dirigée par l'appelant — jamais nul pour un Team Leader correctement configuré. */
    @Transactional(readOnly = true)
    public TeamClassifier.Team requireLedTeam(AuthenticatedUser requester) {
        User leader = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
        if (leader.getLedTeam() == null || leader.getLedTeam().isBlank()) {
            throw ApiException.forbidden("Aucune équipe ne vous a été affectée. Contactez votre administrateur.");
        }
        try {
            return TeamClassifier.Team.valueOf(leader.getLedTeam());
        } catch (IllegalArgumentException e) {
            throw ApiException.forbidden("Affectation d'équipe invalide. Contactez votre administrateur.");
        }
    }

    @Transactional(readOnly = true)
    public List<UserDirectoryResponse> teamMembers(AuthenticatedUser requester) {
        TeamClassifier.Team team = requireLedTeam(requester);
        return userRepository.findAll().stream()
                .filter(u -> TeamClassifier.classify(u.getActivity()) == team)
                .map(this::toDirectory)
                .sorted((a, b) -> String.valueOf(a.fullName()).compareToIgnoreCase(String.valueOf(b.fullName())))
                .toList();
    }

    /** Détails complets (id, contrat, résidence) — pour la fiche agent éditable de l'onglet Membres. */
    @Transactional(readOnly = true)
    public List<UserResponse> teamMembersFull(AuthenticatedUser requester) {
        TeamClassifier.Team team = requireLedTeam(requester);
        return userRepository.findAll().stream()
                .filter(u -> TeamClassifier.classify(u.getActivity()) == team)
                .map(u -> userService.getById(u.getId()))
                .sorted((a, b) -> String.valueOf(a.fullName()).compareToIgnoreCase(String.valueOf(b.fullName())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PerformanceResponse> teamReporting(AuthenticatedUser requester, YearMonth month) {
        TeamClassifier.Team team = requireLedTeam(requester);
        return reportingService.teamSummary(month != null ? month : YearMonth.now()).stream()
                .filter(r -> TeamClassifier.classify(r.activity()) == team)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AttendanceRecordResponse> teamAttendance(AuthenticatedUser requester, LocalDate date) {
        TeamClassifier.Team team = requireLedTeam(requester);
        Set<String> teamUsernames = teamUsernames(team);
        return attendanceService.forDate(date).stream()
                .filter(a -> teamUsernames.contains(a.matricule() == null ? "" : a.matricule().toLowerCase()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<QualityEvaluationResponse> teamQualityEvaluations(AuthenticatedUser requester) {
        TeamClassifier.Team team = requireLedTeam(requester);
        Set<String> teamUsernames = teamUsernames(team);
        return qualityEvaluationService.listAll().stream()
                .filter(e -> teamUsernames.contains(e.agentMatricule() == null ? "" : e.agentMatricule().toLowerCase()))
                .toList();
    }

    private Set<String> teamUsernames(TeamClassifier.Team team) {
        return userRepository.findAll().stream()
                .filter(u -> TeamClassifier.classify(u.getActivity()) == team)
                .map(u -> u.getUsername() == null ? "" : u.getUsername().toLowerCase())
                .collect(Collectors.toSet());
    }

    private UserDirectoryResponse toDirectory(User u) {
        return new UserDirectoryResponse(u.getId() != null ? u.getId().intValue() : null, u.getUsername(),
                u.getName(), u.getEmail(), null, null, u.getAffiliateBranch(), u.getAccountEnabled(),
                u.getActivity(), null);
    }
}

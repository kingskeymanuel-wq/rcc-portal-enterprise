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

    /** Activité écrite sur un agent ajouté à une équipe de canal (Tchat, Rafiki). */
    private static final java.util.Map<String, String> CHANNEL_TO_ACTIVITY = java.util.Map.of("TCHAT", "INBOUND TCHAT", "RAFIKI", "INBOUND RAFIKI");

    /** Code de l'équipe menée tel qu'affecté (INBOUND_VOICE, INBOUND_MAIL, TCHAT, RAFIKI, CIB, OUTBOUND). */
    @Transactional(readOnly = true)
    public String ledTeamCode(AuthenticatedUser requester) {
        User leader = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
        if (leader.getLedTeam() == null || leader.getLedTeam().isBlank()) {
            throw ApiException.forbidden("Aucune équipe ne vous a été affectée. Contactez votre administrateur.");
        }
        return leader.getLedTeam().trim().toUpperCase().replace(' ', '_');
    }

    /** Canal mené (TCHAT / RAFIKI) ou null pour une équipe entière. */
    @Transactional(readOnly = true)
    public String ledChannel(AuthenticatedUser requester) {
        String code = ledTeamCode(requester);
        return TeamClassifier.isChannel(code) ? code : null;
    }

    private static boolean inTeam(String ledCode, User u) {
        return TeamClassifier.belongsTo(ledCode, u.getActivity(), null);
    }

    /**
     * Recherche des agents éligibles à rejoindre mon équipe (pas déjà dedans, pas un compte de
     * management) — pour le bouton "Ajouter un agent" de l'onglet Membres.
     */
    @Transactional(readOnly = true)
    public List<UserDirectoryResponse> searchAddableAgents(AuthenticatedUser requester, String query) {
        String code = ledTeamCode(requester);
        String q = query == null ? "" : query.trim().toLowerCase();
        return userRepository.findAll().stream()
                .filter(u -> !inTeam(code, u))
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
        String code = ledTeamCode(requester);
        TeamClassifier.Team team = TeamClassifier.teamOf(code);
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Unknown user."));
        if (isManagementAccount(userId)) {
            throw ApiException.badRequest("Ce compte porte un rôle de management — il ne peut pas être ajouté à une équipe opérationnelle.");
        }
        user.setActivity(CHANNEL_TO_ACTIVITY.getOrDefault(code, TEAM_TO_ACTIVITY.get(team)));
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
        String code = ledTeamCode(requester);
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Unknown user."));
        if (!inTeam(code, user)) {
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
        // Tchat et Rafiki relèvent de l'Inbound Mail (mêmes indicateurs de reporting), restreints à leur canal ailleurs.
        TeamClassifier.Team team = TeamClassifier.teamOf(leader.getLedTeam());
        if (team == TeamClassifier.Team.OTHER) throw ApiException.forbidden("Affectation d'équipe invalide. Contactez votre administrateur.");
        return team;
    }

    @Transactional(readOnly = true)
    public List<UserDirectoryResponse> teamMembers(AuthenticatedUser requester) {
        String code = ledTeamCode(requester);
        return userRepository.findAll().stream()
                .filter(u -> inTeam(code, u))
                .map(this::toDirectory)
                .sorted((a, b) -> String.valueOf(a.fullName()).compareToIgnoreCase(String.valueOf(b.fullName())))
                .toList();
    }

    /** Détails complets (id, contrat, résidence) — pour la fiche agent éditable de l'onglet Membres. */
    @Transactional(readOnly = true)
    public List<UserResponse> teamMembersFull(AuthenticatedUser requester) {
        String code = ledTeamCode(requester);
        return userRepository.findAll().stream()
                .filter(u -> inTeam(code, u))
                .map(u -> userService.getById(u.getId()))
                .sorted((a, b) -> String.valueOf(a.fullName()).compareToIgnoreCase(String.valueOf(b.fullName())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PerformanceResponse> teamReporting(AuthenticatedUser requester, YearMonth month) {
        String code = ledTeamCode(requester);
        return reportingService.teamSummary(month != null ? month : YearMonth.now()).stream()
                .filter(r -> TeamClassifier.belongsTo(code, r.activity(), null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AttendanceRecordResponse> teamAttendance(AuthenticatedUser requester, LocalDate date) {
        Set<String> teamUsernames = teamUsernames(ledTeamCode(requester));
        return attendanceService.forDate(date).stream()
                .filter(a -> teamUsernames.contains(a.matricule() == null ? "" : a.matricule().toLowerCase()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<QualityEvaluationResponse> teamQualityEvaluations(AuthenticatedUser requester) {
        Set<String> teamUsernames = teamUsernames(ledTeamCode(requester));
        return qualityEvaluationService.listAll().stream()
                .filter(e -> teamUsernames.contains(e.agentMatricule() == null ? "" : e.agentMatricule().toLowerCase()))
                .toList();
    }

    /** Identifiants des agents de l'équipe menée. */
    @Transactional(readOnly = true)
    public Set<String> teamUsernames(AuthenticatedUser requester) {
        return teamUsernames(ledTeamCode(requester));
    }

    private Set<String> teamUsernames(String ledCode) {
        return userRepository.findAll().stream()
                .filter(u -> inTeam(ledCode, u))
                .map(u -> u.getUsername() == null ? "" : u.getUsername().toLowerCase())
                .collect(Collectors.toSet());
    }

    private UserDirectoryResponse toDirectory(User u) {
        return new UserDirectoryResponse(u.getId() != null ? u.getId().intValue() : null, u.getUsername(),
                u.getName(), u.getEmail(), null, null, u.getAffiliateBranch(), u.getAccountEnabled(),
                u.getActivity(), null);
    }
}

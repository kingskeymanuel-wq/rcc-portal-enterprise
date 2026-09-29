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
    private HrOrganizationService hrOrganization;

    private com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServices;
    private com.ecobank.rccportal.repository.RccServiceRepository services;
    private com.ecobank.rccportal.repository.RccNotificationRepository notifications;

    /** Synchronisation du service agent et notifications — injection facultative (absente des tests unitaires). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSync(com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServices,
                 com.ecobank.rccportal.repository.RccServiceRepository services,
                 com.ecobank.rccportal.repository.RccNotificationRepository notifications) {
        this.userServices = userServices;
        this.services = services;
        this.notifications = notifications;
    }

    /** Sorties d'agents tracées côté RH — injection facultative (absente des tests unitaires). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setHrOrganization(HrOrganizationService hrOrganization) {
        this.hrOrganization = hrOrganization;
    }

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
        return managementLabel(userId) != null;
    }

    /** Rôles de base qui font d'un compte un compte de management/support (jamais membre d'une équipe). */
    private static final java.util.Set<String> MANAGEMENT_ROLES = java.util.Set.of("ADMIN", "RH", "SUPERVISOR", "TEAM_LEADER", "EXCELLIAM", "AGENCE");

    /**
     * Libellé du profil de management d'un compte (« Team Leader », « RH »…), null pour un agent. Les rôles
     * d'agent (« agent », « Agent Inbound », « Agent Tchat »…) et Qualité ne comptent pas : l'ancienne règle
     * (« tout rôle autre que AGENT ») excluait à tort la plupart des agents de la recherche.
     */
    String managementLabel(Long userId) {
        List<String> roleNames = userRoleRepository.findRolesByUserId(userId).stream()
                .map(ur -> ur.getRole() != null ? ur.getRole().getName() : null).filter(java.util.Objects::nonNull).toList();
        return managementLabel(roleNames, currentServiceCodes(userId));
    }

    static String managementLabel(List<String> roleNames, List<String> serviceCodes) {
        for (String n : roleNames) {
            String base = com.ecobank.rccportal.security.AccessResolver.roleOfName(n);
            if (base != null && MANAGEMENT_ROLES.contains(base)) return MANAGEMENT_LABELS.getOrDefault(base, base);
        }
        for (String code : serviceCodes) {
            String c = code == null ? "" : code.toUpperCase();
            if (c.startsWith("TEAM_LEADER_")) return "Team Leader";
            if (c.equals("SUPERVISEUR_QA")) return "Head QA";
            if (c.equals("SUPERVISEUR")) return "Superviseur";
            if (c.equals("RH")) return "RH";
        }
        return null;
    }

    private static final java.util.Map<String, String> MANAGEMENT_LABELS = java.util.Map.of("ADMIN", "Administrateur", "RH", "RH",
            "SUPERVISOR", "Superviseur", "TEAM_LEADER", "Team Leader", "EXCELLIAM", "Excelliam", "AGENCE", "Agence");

    /** Service agent de chaque équipe : aligné automatiquement quand un agent change d'équipe. */
    static final java.util.Map<String, String> TEAM_TO_AGENT_SERVICE = java.util.Map.of(
            "INBOUND_VOICE", "AGENT_INBOUND", "INBOUND_MAIL", "AGENT_INBOUND_MAIL", "TCHAT", "AGENT_TCHAT",
            "RAFIKI", "AGENT_RAFIKI", "CIB", "AGENT_CIB", "OUTBOUND", "AGENT_OUTBOUND");

    static final java.util.Map<String, String> TEAM_LABELS = java.util.Map.of("INBOUND_VOICE", "Inbound Voix", "INBOUND_MAIL", "Inbound Mail",
            "TCHAT", "Tchat", "RAFIKI", "Rafiki", "CIB", "CIB", "OUTBOUND", "Outbound");

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

    private boolean inTeam(String ledCode, User u) {
        return TeamClassifier.belongsTo(ledCode, u.getActivity(), null);
    }

    /** Personne trouvée par la recherche élargie de l'onglet Membres. */
    public record Candidate(Long id, String username, String fullName, String email, String country, String team, String teamLabel,
                            String currentLeader, List<String> services, boolean active, boolean inMyTeam, boolean addable, String reason) {}

    /**
     * Recherche élargie : tout le monde (toutes équipes, toutes filiales, sans équipe), sur le nom, l'identifiant,
     * l'e-mail, l'équipe, le service ou la filiale. Chaque résultat indique son équipe actuelle et son Team Leader ;
     * un agent d'une autre équipe peut être transféré dans la mienne. Seuls les comptes de management et les comptes
     * désactivés ne peuvent pas être ajoutés (la raison est affichée).
     */
    @Transactional(readOnly = true)
    public List<Candidate> searchCandidates(AuthenticatedUser requester, String query) {
        String code = ledTeamCode(requester);
        String q = fold(query);
        // Rôles et services de tout le monde en deux requêtes (et non plusieurs par personne) : recherche rapide.
        java.util.Map<Long, List<String>> codes = new java.util.HashMap<>();
        java.util.Map<Long, List<String>> names = new java.util.HashMap<>();
        if (userServices != null) {
            for (var a : userServices.findAll()) {
                if (a.getService() == null || a.getUser() == null) continue;
                Long uid = a.getUser().getId();
                if (a.getService().getCode() != null) codes.computeIfAbsent(uid, k -> new java.util.ArrayList<>()).add(a.getService().getCode());
                names.computeIfAbsent(uid, k -> new java.util.ArrayList<>()).add(a.getService().getName() != null ? a.getService().getName() : a.getService().getCode());
            }
        }
        java.util.Map<Long, List<String>> roles = new java.util.HashMap<>();
        for (var ur : userRoleRepository.findAll()) {
            if (ur.getUser() != null && ur.getRole() != null) roles.computeIfAbsent(ur.getUser().getId(), k -> new java.util.ArrayList<>()).add(ur.getRole().getName());
        }
        List<User> leaders = userRepository.findAll().stream().filter(u -> u.getLedTeam() != null && !u.getLedTeam().isBlank()).toList();
        List<Candidate> out = new java.util.ArrayList<>();
        for (User u : userRepository.findAll()) {
            if (u.getUsername() != null && u.getUsername().equalsIgnoreCase(requester.username())) continue;
            List<String> c = codes.getOrDefault(u.getId(), List.of());
            String team = teamCodeOf(u.getActivity(), c);
            String teamLabel = team == null ? "Sans équipe" : TEAM_LABELS.getOrDefault(team, team);
            String country = HrOrganizationService.countryOf(u.getAffiliateBranch());
            if (!q.isEmpty()) {
                String hay = fold(String.join(" ", String.valueOf(u.getName()), String.valueOf(u.getUsername()), String.valueOf(u.getEmail()),
                        String.valueOf(u.getActivity()), teamLabel, country, "CI".equals(country) ? "cote d'ivoire" : "togo",
                        String.join(" ", names.getOrDefault(u.getId(), List.of()))));
                boolean all = true;
                for (String word : q.split("\\s+")) if (!hay.contains(word)) { all = false; break; }
                if (!all) continue;
            }
            boolean active = !Boolean.FALSE.equals(u.getAccountEnabled());
            boolean mine = TeamClassifier.belongsTo(code, u.getActivity(), c);
            String management = managementLabel(roles.getOrDefault(u.getId(), List.of()), c);
            String reason = mine ? "Déjà dans votre équipe" : management != null ? "Compte de management (" + management + ")"
                    : !active ? "Compte désactivé — réintégration par le RH" : null;
            User leader = team == null ? null : TeamClassifier.leaderFor(u.getActivity(), leaders, User::getLedTeam);
            out.add(new Candidate(u.getId(), u.getUsername(), u.getName() != null ? u.getName() : u.getUsername(), u.getEmail(), country,
                    team, teamLabel, leader == null ? null : (leader.getName() != null ? leader.getName() : leader.getUsername()),
                    names.getOrDefault(u.getId(), List.of()), active, mine, reason == null, reason));
        }
        out.sort(java.util.Comparator.comparing((Candidate x) -> x.addable() ? 0 : x.inMyTeam() ? 2 : 1)
                .thenComparing(x -> x.team() == null ? 0 : 1)
                .thenComparing(x -> String.valueOf(x.fullName()), String.CASE_INSENSITIVE_ORDER));
        return out.size() > 60 ? out.subList(0, 60) : out;
    }

    /** Compatibilité : ancien format (annuaire) des seules personnes ajoutables. */
    @Transactional(readOnly = true)
    public List<UserDirectoryResponse> searchAddableAgents(AuthenticatedUser requester, String query) {
        return searchCandidates(requester, query).stream().filter(Candidate::addable)
                .map(c -> userRepository.findById(c.id()).map(this::toDirectory).orElse(null)).filter(java.util.Objects::nonNull).toList();
    }

    static String teamCodeOf(String activity, List<String> serviceCodes) {
        String channel = TeamClassifier.channel(activity, serviceCodes);
        if (channel != null) return channel;
        TeamClassifier.Team t = TeamClassifier.classify(activity, serviceCodes);
        return t == TeamClassifier.Team.OTHER ? null : t.name();
    }

    private static String fold(String s) {
        return s == null ? "" : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().trim();
    }

    /**
     * Ajoute une personne à mon équipe (y compris un agent d'une autre équipe : transfert). Synchronisation
     * automatique : son équipe (activity) et son service agent sont alignés sur mon équipe — son portail, son
     * planning, son reporting, la QA et le RH le voient aussitôt dans mon équipe ; lui et son ancien Team
     * Leader sont notifiés.
     */
    @Transactional
    public void addMember(AuthenticatedUser requester, Long userId) {
        String code = ledTeamCode(requester);
        TeamClassifier.Team team = TeamClassifier.teamOf(code);
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Unknown user."));
        String management = managementLabel(userId);
        if (management != null) {
            throw ApiException.badRequest("Ce compte est un compte de management (" + management + ") : il ne peut pas être ajouté à une équipe opérationnelle.");
        }
        if (Boolean.FALSE.equals(user.getAccountEnabled())) {
            throw ApiException.badRequest("Ce compte est désactivé : sa réintégration se fait depuis le portail RH (onglet Sorties).");
        }
        List<String> before = currentServiceCodes(userId);
        String oldTeam = teamCodeOf(user.getActivity(), before);
        User oldLeader = null;
        if (oldTeam != null && !oldTeam.equals(code)) {
            List<User> leaders = userRepository.findAll().stream().filter(u -> u.getLedTeam() != null && !u.getLedTeam().isBlank()).toList();
            oldLeader = TeamClassifier.leaderFor(user.getActivity(), leaders, User::getLedTeam);
        }
        user.setActivity(CHANNEL_TO_ACTIVITY.getOrDefault(code, TEAM_TO_ACTIVITY.get(team)));
        userRepository.save(user);
        syncAgentService(user, code);

        String leaderName = requester.name() != null && !requester.name().isBlank() ? requester.name() : requester.username();
        String teamLabel = TEAM_LABELS.getOrDefault(code, code);
        notify(user, "Vous avez rejoint l'équipe " + teamLabel + " de " + leaderName + ". Votre portail, votre planning et votre suivi sont mis à jour.");
        if (oldLeader != null && (oldLeader.getUsername() == null || !oldLeader.getUsername().equalsIgnoreCase(requester.username()))) {
            notify(oldLeader, (user.getName() != null ? user.getName() : user.getUsername()) + " a été transféré(e) de votre équipe vers l'équipe "
                    + teamLabel + " de " + leaderName + ".");
        }
    }

    private List<String> currentServiceCodes(Long userId) {
        if (userServices == null) return List.of();
        return userServices.findServicesByUserId(userId).stream()
                .filter(a -> a.getService() != null && a.getService().getCode() != null)
                .map(a -> a.getService().getCode()).toList();
    }

    /** Un seul service agent, celui de la nouvelle équipe ; les autres services (QA, formation…) restent. */
    private void syncAgentService(User user, String teamCode) {
        if (userServices == null || services == null) return;
        String wanted = TEAM_TO_AGENT_SERVICE.get(teamCode);
        if (wanted == null) return;
        boolean has = false;
        for (var a : userServices.findByUserId(user.getId())) {
            String c = a.getService() == null || a.getService().getCode() == null ? "" : a.getService().getCode().toUpperCase();
            if (c.equals(wanted)) { has = true; continue; }
            if (TEAM_TO_AGENT_SERVICE.containsValue(c)) userServices.delete(a);
        }
        if (!has) {
            services.findByCodeIgnoreCase(wanted).ifPresent(svc -> userServices.save(
                    com.ecobank.rccportal.model.UserServiceAssignment.builder().user(user).service(svc).build()));
        }
    }

    private void notify(User target, String content) {
        if (notifications == null || target == null) return;
        try {
            notifications.save(com.ecobank.rccportal.model.RccNotification.builder().targetUser(target).content(content).isRead(false).build());
        } catch (RuntimeException ignored) {
            // notification best-effort
        }
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
        removeMember(requester, userId, resigned ? "DEPARTURE" : "TRANSFER", resigned ? "DEMISSION" : null, null);
    }

    /**
     * Retrait d'un agent de mon équipe :
     * <ul>
     *   <li>TRANSFER : il quitte seulement mon équipe (compte actif, à placer ailleurs par un autre Team Leader ou le RH) ;</li>
     *   <li>DEPARTURE : il quitte le centre (démission, fin de contrat, fin de stage…) — compte désactivé et sortie
     *       tracée côté RH (onglet Sorties, réintégration possible), motif obligatoire.</li>
     * </ul>
     */
    @Transactional
    public void removeMember(AuthenticatedUser requester, Long userId, String mode, String reason, String comment) {
        String code = ledTeamCode(requester);
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Unknown user."));
        if (!inTeam(code, user)) {
            throw ApiException.forbidden("Cet agent ne fait pas partie de votre équipe.");
        }
        if ("DEPARTURE".equalsIgnoreCase(mode)) {
            if (reason == null || !HrOrganizationService.DEPARTURE_REASONS.contains(reason)) {
                throw ApiException.badRequest("Choisissez le motif de la sortie.");
            }
            String note = "Retiré par son Team Leader (" + requester.username() + ")" + (comment == null || comment.isBlank() ? "" : " — " + comment.trim());
            if (hrOrganization != null) {
                hrOrganization.recordDepartureForTeamLeader(requester.username(), userId, reason, note);
                user = userRepository.findById(userId).orElse(user);
            }
            user.setAccountEnabled(false);
        }
        user.setActivity(null);
        userRepository.save(user);
    }

    /** Appartenance à l'équipe menée par l'appelant (même règle que la liste des membres). */
    @Transactional(readOnly = true)
    public java.util.function.Predicate<User> memberPredicate(AuthenticatedUser requester) {
        String code = ledTeamCode(requester);
        return u -> u != null && inTeam(code, u);
    }

    /** Prévient chaque membre actif de l'équipe (ex. : KPI du mois importés par le Team Leader). */
    @Transactional
    public int notifyTeam(AuthenticatedUser requester, String content) {
        String code = ledTeamCode(requester);
        int n = 0;
        for (User u : userRepository.findAll()) {
            if (inTeam(code, u) && !Boolean.FALSE.equals(u.getAccountEnabled())) { notify(u, content); n++; }
        }
        return n;
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

package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CreateUserRequest;
import com.ecobank.rccportal.dto.PendingAccountResponse;
import com.ecobank.rccportal.dto.UpdateUserRequest;
import com.ecobank.rccportal.dto.UserResponse;
import com.ecobank.rccportal.model.RccService;
import com.ecobank.rccportal.model.Role;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserRole;
import com.ecobank.rccportal.model.UserServiceAssignment;
import com.ecobank.rccportal.repository.RccServiceRepository;
import com.ecobank.rccportal.repository.RoleRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import com.ecobank.rccportal.util.ApiException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
public class UserService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final RoleRepository roleRepository;
    private final RccServiceRepository rccServiceRepository;
    private final com.ecobank.rccportal.repository.UserProfileRepository userProfileRepository;
    private final NotificationService notificationService;
    private final com.ecobank.rccportal.repository.RefreshTokenRepository refreshTokenRepository;

    public UserService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            UserServiceAssignmentRepository userServiceAssignmentRepository,
            RoleRepository roleRepository,
            RccServiceRepository rccServiceRepository,
            com.ecobank.rccportal.repository.UserProfileRepository userProfileRepository,
            NotificationService notificationService,
            com.ecobank.rccportal.repository.RefreshTokenRepository refreshTokenRepository) {

        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
        this.roleRepository = roleRepository;
        this.rccServiceRepository = rccServiceRepository;
        this.userProfileRepository = userProfileRepository;
        this.notificationService = notificationService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * Retourne tous les utilisateurs RCC.
     */
    @Transactional(readOnly = true)
    public List<UserResponse> listAll() {

        return userRepository
                .findAll()
                .stream()
                .map(this::toResponse)
                .sorted(
                        Comparator.comparing(
                                UserResponse::fullName,
                                Comparator.nullsLast(
                                        String.CASE_INSENSITIVE_ORDER
                                )
                        )
                )
                .toList();
    }

    /**
     * Redirection automatique vers le bon portail à la connexion — voir SERVICE_PORTAL_REDIRECTS
     * ci-dessous. L'écran de première connexion "team-setup" (choix filiale/service/équipe
     * obligatoire) a été retiré du projet à la demande : accès direct au portail dans tous les
     * cas, plus aucun blocage possible ici.
     */
    @Transactional
    public com.ecobank.rccportal.dto.TeamStatusResponse teamStatus(String username) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        String redirectTo = null;
        List<UserRole> roles = userRoleRepository.findRolesByUserId(user.getId());
        boolean isAdmin = roles.stream().anyMatch(r ->
                r.getRole() != null && "ADMIN".equalsIgnoreCase(
                        r.getRole().getName() != null ? r.getRole().getName().trim() : null));

        // Redirection automatique vers le portail métier propre au SERVICE attribué depuis
        // Administration → fiche utilisateur → Services (voir SERVICE_PORTAL_REDIRECTS
        // ci-dessous). C'est le point d'entrée voulu : dès qu'un service comme "Team Leader
        // Outbound", "Agent Inbound", "RH"... est attribué puis enregistré, la personne
        // atterrit automatiquement sur le portail correspondant à sa prochaine connexion —
        // recalculé à chaque connexion depuis l'état actuel en base, jamais mis en cache.
        // Un compte ADMIN garde toujours le tableau de bord général, même s'il porte en plus
        // un de ces services (utile pour les comptes de test) — voir CurrentUserAccessService
        // pour la même convention.
        if (!isAdmin) {
            List<UserServiceAssignment> services =
                    userServiceAssignmentRepository.findServicesByUserId(user.getId());
            redirectTo = SERVICE_PORTAL_REDIRECTS.entrySet().stream()
                    .filter(entry -> services.stream().anyMatch(s ->
                            s.getService() != null && entry.getKey().equalsIgnoreCase(
                                    s.getService().getCode() != null ? s.getService().getCode().trim() : null)))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        // Repli — anciennes attributions faites via un Rôle plutôt qu'un Service (voir
        // ROLE_PORTAL_REDIRECTS), pour ne rien casser tant que les comptes existants n'ont
        // pas été re-attribués via Services.
        if (redirectTo == null && !isAdmin) {
            redirectTo = ROLE_PORTAL_REDIRECTS.entrySet().stream()
                    .filter(entry -> roles.stream().anyMatch(r ->
                            r.getRole() != null && entry.getKey().equalsIgnoreCase(
                                    r.getRole().getName() != null ? r.getRole().getName().trim() : null)))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        // Redirection Outbound réservée aux simples agents (dernier repli, si ni Service ni
        // Rôle métier ci-dessus ne correspond) — un QA/Admin/RH/Superviseur/Team Leader dont
        // l'ACTIVITY personnelle contiendrait "OUTBOUND" ne doit pas être détourné de son
        // propre portail vers le tableau de bord Outbound.
        if (redirectTo == null) {
            boolean isPlainAgent = roles.stream().noneMatch(r -> {
                String n = r.getRole() != null ? r.getRole().getName() : null;
                return n != null && !"AGENT".equalsIgnoreCase(n.trim());
            });
            if (isPlainAgent && com.ecobank.rccportal.util.TeamClassifier.classify(user.getActivity())
                    == com.ecobank.rccportal.util.TeamClassifier.Team.OUTBOUND) {
                redirectTo = "/outbound-dashboard";
            }
        }

        return new com.ecobank.rccportal.dto.TeamStatusResponse(false, redirectTo);
    }

    /**
     * Portail métier vers lequel rediriger automatiquement l'utilisateur juste après sa
     * connexion (voir teamStatus() et /api/users/me/team-status), selon le SERVICE qui lui a été
     * attribué depuis Administration → fiche utilisateur → Services (voir
     * WorkflowSchemaBootstrap pour la création de ces 7 services). Ordre = priorité en cas de
     * cumul de plusieurs services : le premier trouvé l'emporte. LinkedHashMap pour préserver cet
     * ordre. Clés = CODE réel du service (dbo.SERVICES.CODE), comparées insensible à la casse.
     */
    private static final java.util.LinkedHashMap<String, String> SERVICE_PORTAL_REDIRECTS =
            new java.util.LinkedHashMap<>() {{
                put("RH", "/rh");
                put("SUPERVISEUR", "/supervisor");
                put("SUPERVISEUR_QA", "/qa-supervisor");
                put("QUALITY_ASSURANCE", "/qa");
                put("FORMATEUR", "/training");
                put("TEAM_LEADER_INBOUND_VOICE", "/team-leader");
                put("TEAM_LEADER_INBOUND_MAIL", "/team-leader");
                put("TEAM_LEADER_OUTBOUND", "/team-leader");
                put("AGENT_OUTBOUND", "/outbound-dashboard");
                put("AGENT_INBOUND", "/dashboard");
                put("AGENT_INBOUND_MAIL", "/dashboard");
                put("AGENT_CIB", "/dashboard");
            }};

    /** Repli — voir commentaire sur son usage dans teamStatus(). Clés = noms de Rôle réels. */
    private static final java.util.LinkedHashMap<String, String> ROLE_PORTAL_REDIRECTS =
            new java.util.LinkedHashMap<>() {{
                put("RH", "/rh");
                put("Head RCC (Superviseur)", "/supervisor");
                put("Quality Assurance", "/qa");
                put("Team Leader Inbound Voice", "/team-leader");
                put("Team Leader Inbound Mail", "/team-leader");
                put("Team Leader Outbound", "/team-leader");
            }};

    /** "Mon Parcours" RH — dates et type de contrat de chaque agent, filtré sur la filiale du RH (l'admin voit tout). */
    @Transactional(readOnly = true)
    public List<UserResponse> listContractsForHr(String hrUsername, boolean isAdmin) {
        User hr = userRepository.findFirstByUsernameIgnoreCase(hrUsername)
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        return userRepository.findAll().stream()
                .filter(u -> isAdmin || hr.getAffiliateBranch() == null
                        || hr.getAffiliateBranch().equalsIgnoreCase(u.getAffiliateBranch()))
                .map(this::toResponse)
                .sorted(Comparator.comparing(UserResponse::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }


    @Transactional(readOnly = true)
    public List<UserResponse> search(String query) {
        String q = query.trim().toLowerCase();
        return userRepository.findAll().stream()
                .map(this::toResponse)
                .filter(u -> (u.fullName() != null && u.fullName().toLowerCase().contains(q))
                        || (u.username() != null && u.username().toLowerCase().contains(q))
                        || (u.service() != null && u.service().toLowerCase().contains(q))
                        || (u.affiliateBranch() != null && u.affiliateBranch().toLowerCase().contains(q)))
                .sorted(Comparator.comparing(UserResponse::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(30)
                .toList();
    }

    /** Édition des informations de base + champs RH — le rôle et le service se gèrent via leurs propres endpoints. */
    @Transactional
    public UserResponse updateUser(Long userId, UpdateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));

        if (request.username() != null && !request.username().isBlank()) {
            String newUsername = request.username().trim();
            if (newUsername.length() > 35) {
                throw ApiException.badRequest("username_too_long",
                        "Le nom d'utilisateur ne doit pas dépasser 35 caractères (actuellement " + newUsername.length() + ").");
            }
            if (!newUsername.equalsIgnoreCase(user.getUsername())) {
                userRepository.findFirstByUsernameIgnoreCase(newUsername).ifPresent(existing -> {
                    if (!existing.getId().equals(user.getId())) {
                        throw ApiException.conflict("username_taken", "Ce nom d'utilisateur est déjà pris par un autre compte.");
                    }
                });
                user.setUsername(newUsername);
            }
        }
        if (request.name() != null) user.setName(request.name().trim());
        if (request.email() != null) user.setEmail(request.email().trim());
        if (request.affiliateBranch() != null) user.setAffiliateBranch(request.affiliateBranch().isBlank() ? null : request.affiliateBranch().trim());
        if (request.gender() != null) user.setGender(request.gender().isBlank() ? null : request.gender().trim());
        if (request.contractType() != null) user.setContractType(request.contractType().isBlank() ? null : request.contractType().trim());
        if (request.contractStatus() != null) user.setContractStatus(request.contractStatus().isBlank() ? null : request.contractStatus().trim());
        if (request.contractStartDate() != null) user.setContractStartDate(request.contractStartDate());
        if (request.contractEndDate() != null) user.setContractEndDate(request.contractEndDate());
        if (request.activity() != null) user.setActivity(request.activity().isBlank() ? null : request.activity().trim());
        if (request.residencePlace() != null) user.setResidencePlace(request.residencePlace().isBlank() ? null : request.residencePlace().trim());
        if (request.ledTeam() != null) {
            String lt = request.ledTeam().isBlank() ? null : request.ledTeam().trim().toUpperCase();
            if (lt != null && !java.util.Set.of("INBOUND_VOICE", "INBOUND_MAIL", "CIB", "OUTBOUND").contains(lt)) {
                throw ApiException.badRequest("ledTeam must be one of INBOUND_VOICE, INBOUND_MAIL, CIB, OUTBOUND.");
            }
            user.setLedTeam(lt);
        }

        return toResponse(userRepository.save(user));
    }

    /**
     * Édition restreinte — contrat + résidence uniquement (voir UpdateHrFieldsRequest).
     * Ouverte à RH/Superviseur/Admin (tout agent) et Team Leader (seulement sa propre
     * équipe — vérifié par requesterCanEditHrFields ci-dessous, appelé par le controller).
     */
    @Transactional
    public UserResponse updateHrFields(Long userId, com.ecobank.rccportal.dto.UpdateHrFieldsRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));

        if (request.contractType() != null) user.setContractType(request.contractType().isBlank() ? null : request.contractType().trim());
        if (request.contractStatus() != null) user.setContractStatus(request.contractStatus().isBlank() ? null : request.contractStatus().trim());
        if (request.contractStartDate() != null) user.setContractStartDate(request.contractStartDate());
        if (request.contractEndDate() != null) user.setContractEndDate(request.contractEndDate());
        if (request.residencePlace() != null) user.setResidencePlace(request.residencePlace().isBlank() ? null : request.residencePlace().trim());

        return toResponse(userRepository.save(user));
    }

    /** true si ce Team Leader peut éditer les champs RH de cet agent — seulement sa propre équipe. */
    @Transactional(readOnly = true)
    public boolean isOwnTeamMember(String teamLeaderUsername, Long targetUserId) {
        User leader = userRepository.findFirstByUsernameIgnoreCase(teamLeaderUsername).orElse(null);
        User target = userRepository.findById(targetUserId).orElse(null);
        if (leader == null || target == null || leader.getLedTeam() == null) return false;
        return leader.getLedTeam().equalsIgnoreCase(com.ecobank.rccportal.util.TeamClassifier.classify(target.getActivity()).name());
    }

    @Transactional
    public UserResponse setAccountEnabled(Long userId, boolean enabled) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));
        user.setAccountEnabled(enabled);
        return toResponse(userRepository.save(user));
    }

    /** Sessions actives (non expirées) d'un utilisateur — "gérer les connexions" côté Administration. */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.ActiveSessionResponse> listActiveSessions(Long userId) {
        if (!userRepository.existsById(userId)) throw ApiException.notFound("Unknown user.");
        return refreshTokenRepository.findByUser_IdAndExpiresAtAfterOrderByCreatedAtDesc(userId, java.time.LocalDateTime.now())
                .stream()
                .map(t -> new com.ecobank.rccportal.dto.ActiveSessionResponse(t.getRefreshTokenId(), t.getCreatedAt(), t.getExpiresAt()))
                .toList();
    }

    /** Déconnexion forcée — révoque toutes les sessions de l'utilisateur, sur toutes ses
     *  machines. Prochaine requête de sa part échoue, il doit se reconnecter. */
    @Transactional
    public long revokeAllSessions(Long userId) {
        if (!userRepository.existsById(userId)) throw ApiException.notFound("Unknown user.");
        return refreshTokenRepository.deleteByUser_Id(userId);
    }

    /**
     * Création directe par un administrateur — le compte est actif immédiatement
     * (statut APPROVED, ACCOUNT_ENABLED=true), sans passer par le circuit
     * d'auto-inscription/approbation. La personne peut se connecter dès que son
     * compte AD existe, sans intervention manuelle en base de données.
     */
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        if (username.isBlank()) {
            throw ApiException.badRequest("Username is required.");
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw ApiException.badRequest("A user with this username already exists.");
        }

        User user = User.builder()
                .username(username)
                .name(request.name())
                .email(request.email())
                .affiliateBranch(request.affiliateBranch())
                .status(STATUS_APPROVED)
                .accountEnabled(true)
                .accountLocked(false)
                .accountExpired(false)
                .credentialsExpired(false)
                .failedAttempts(0)
                .build();
        user = userRepository.save(user);

        if (request.roleName() != null && !request.roleName().isBlank()) {
            Role role = roleRepository.findByNameIgnoreCase(request.roleName().trim())
                    .orElseGet(() -> roleRepository.save(Role.builder().name(request.roleName().trim().toUpperCase()).build()));
            userRoleRepository.save(UserRole.builder().user(user).role(role).build());
        }

        if (request.serviceCode() != null && !request.serviceCode().isBlank()) {
            RccService service = rccServiceRepository.findByCodeIgnoreCase(request.serviceCode().trim())
                    .orElseThrow(() -> ApiException.badRequest("Unknown service: " + request.serviceCode()));
            userServiceAssignmentRepository.save(UserServiceAssignment.builder().user(user).service(service).build());
        }

        log.info("User created directly by admin (username={})", user.getUsername());
        return toResponse(user);
    }

    /** Utilisateurs portant un rôle précis (ex. "RESPONSABLE") — sert à peupler la liste des personnes désignées. */
    @Transactional(readOnly = true)
    public List<UserResponse> listByRole(String roleName) {
        return userRoleRepository.findByRoleNameIgnoreCase(roleName).stream()
                .map(UserRole::getUser)
                .distinct()
                .map(this::toResponse)
                .sorted(Comparator.comparing(UserResponse::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** Auto-lookup équipe + Team Leader — pour le formulaire "Évaluer un appel" (QA). */
    @Transactional(readOnly = true)
    public com.ecobank.rccportal.dto.AgentTeamInfoResponse findAgentTeamInfo(String username) {
        User agent = userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.notFound("Agent introuvable : " + username));

        var team = com.ecobank.rccportal.util.TeamClassifier.classify(agent.getActivity());
        String teamLabel = team == com.ecobank.rccportal.util.TeamClassifier.Team.OTHER ? "Non classée" : team.label;

        User teamLeader = team != com.ecobank.rccportal.util.TeamClassifier.Team.OTHER
                ? userRepository.findAll().stream()
                        .filter(u -> team.name().equalsIgnoreCase(u.getLedTeam()) && hasRole(u, "team_leader"))
                        .findFirst().orElse(null)
                : null;

        String photoUrl = userProfileRepository.findByUser(agent)
                .map(com.ecobank.rccportal.model.UserProfile::getPhotoUrl)
                .orElse(null);

        return new com.ecobank.rccportal.dto.AgentTeamInfoResponse(
                agent.getUsername(),
                agent.getName() != null ? agent.getName() : agent.getUsername(),
                photoUrl,
                team.name(),
                teamLabel,
                teamLeader != null ? teamLeader.getUsername() : null,
                teamLeader != null ? (teamLeader.getName() != null ? teamLeader.getName() : teamLeader.getUsername()) : null
        );
    }

    /**
     * Retourne un utilisateur précis à partir de son ID — utilisé par la fiche
     * détaillée combinée (infos + rôles + services).
     */
    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {

        if (id == null) {
            throw ApiException.badRequest("User id is required.");
        }

        User user = userRepository
                .findById(id)
                .orElseThrow(
                        () -> ApiException.notFound("Unknown user.")
                );

        return toResponse(user);
    }

    /**
     * Retourne les comptes en attente de validation.
     */
    @Transactional(readOnly = true)
    public List<PendingAccountResponse> listPending() {

        return userRepository
                .findByStatus(STATUS_PENDING)
                .stream()
                .map(this::toPendingResponse)
                .sorted(
                        Comparator.comparing(
                                PendingAccountResponse::requestedAt,
                                Comparator.nullsLast(
                                        Comparator.naturalOrder()
                                )
                        )
                )
                .toList();
    }

    /**
     * Approuve un compte utilisateur : passe le statut à APPROVED (sinon le compte
     * resterait affiché indéfiniment comme "en attente"), déverrouille le compte,
     * et remet le compteur d'échecs à zéro.
     */
    @Transactional
    public void approve(String username) {

        User user = findByUsername(username);

        user.setStatus(STATUS_APPROVED);
        user.setFailedAttempts(0);
        user.setAccountLocked(false);
        user.setAccountEnabled(true);

        userRepository.save(user);

        // Le blocage de connexion se fait sur l'absence de rôle (voir AuthService.assertAccountUsable),
        // pas sur le statut — sans ça, "Approuver" seul ne débloquerait rien pour un compte
        // fraîchement auto-provisionné depuis l'AD (voir AuthService.provisionUserFromAd), qui
        // n'a justement aucun rôle tant qu'un admin ne lui en attribue pas un. AGENT par défaut,
        // modifiable ensuite via Administration > Utilisateurs.
        if (userRoleRepository.findRolesByUserId(user.getId()).isEmpty()) {
            Role agentRole = roleRepository.findByNameIgnoreCase("agent")
                    .orElseGet(() -> roleRepository.save(Role.builder().name("AGENT").description("Agent").build()));
            userRoleRepository.save(UserRole.builder().user(user).role(agentRole).build());
        }

        log.info(
                "Access request approved (username={})",
                user.getUsername()
        );

        if (user.getEmail() != null &&
                !user.getEmail().isBlank()) {

            notificationService.sendAccountStatusEmail(
                    user.getUsername(),
                    user.getEmail(),
                    true
            );
        }
    }

    /**
     * Réactive un compte verrouillé après échecs de connexion — sans toucher au statut
     * d'approbation ni à accountEnabled (contrairement à approve(), destiné aux demandes
     * d'accès en attente). Appelé depuis le bouton "Réactiver" d'une notification IT.
     */
    @Transactional
    public void unlock(String username) {

        User user = findByUsername(username);

        user.setFailedAttempts(0);
        user.setAccountLocked(false);

        userRepository.save(user);

        log.info("Account unlocked by administrator (username={})", user.getUsername());
    }

    /**
     * Refuse un compte utilisateur : passe le statut à REJECTED et désactive le
     * compte (sinon "refuser" ne changeait rien de réel — le compte restait
     * utilisable).
     */
    @Transactional
    public void reject(String username) {

        User user = findByUsername(username);

        user.setStatus(STATUS_REJECTED);
        user.setAccountEnabled(false);

        userRepository.save(user);

        log.info(
                "Access request rejected (username={})",
                user.getUsername()
        );

        if (user.getEmail() != null &&
                !user.getEmail().isBlank()) {

            notificationService.sendAccountStatusEmail(
                    user.getUsername(),
                    user.getEmail(),
                    false
            );
        }
    }

    /**
     * Recherche un utilisateur à partir de USERNAME.
     */
    private User findByUsername(String username) {

        String normalized =
                username == null
                        ? ""
                        : username.trim();

        if (normalized.isBlank()) {

            throw ApiException.badRequest(
                    "Username is required."
            );
        }

        return userRepository
                .findFirstByUsernameIgnoreCase(normalized)
                .orElseThrow(
                        () -> ApiException.notFound(
                                "Unknown user."
                        )
                );
    }

    /**
     * Transforme User vers UserResponse.
     */
    private UserResponse toResponse(User user) {

        String role = getPrimaryRole(user);
        String service = getPrimaryService(user);

        Integer id =
                user.getId() != null
                        ? user.getId().intValue()
                        : null;

        Boolean active =
                Boolean.TRUE.equals(user.getAccountEnabled());

        String photoUrl = userProfileRepository.findByUser(user)
                .map(com.ecobank.rccportal.model.UserProfile::getPhotoUrl)
                .orElse(null);

        return new UserResponse(
                id,
                user.getUsername(),
                user.getName(),
                user.getEmail(),
                role,
                service,
                user.getAffiliateBranch(),
                active,
                user.getGender(),
                user.getContractType(),
                user.getContractStatus(),
                user.getContractStartDate(),
                user.getContractEndDate(),
                user.getActivity(),
                photoUrl,
                user.getResidencePlace(),
                user.getLedTeam()
        );
    }

    /**
     * Transforme User vers PendingAccountResponse.
     */
    private PendingAccountResponse toPendingResponse(
            User user) {

        String service = getPrimaryService(user);

        return new PendingAccountResponse(
                user.getUsername(),
                user.getName(),
                user.getEmail(),
                service,
                user.getCreatedAt()
        );
    }

    /** true si l'utilisateur porte ce rôle (comparaison insensible à la casse) — User n'a pas
     *  de champ "role" direct, il passe toujours par UserRoles (voir getPrimaryRole()). */
    private boolean hasRole(User user, String roleName) {
        if (user == null || user.getId() == null) return false;
        return userRoleRepository.findRolesByUserId(user.getId()).stream()
                .anyMatch(ur -> ur.getRole() != null && roleName.equalsIgnoreCase(ur.getRole().getName()));
    }

    /**
     * Retourne le rôle principal de l'utilisateur.
     *
     * Relation :
     *
     * USERS
     *   ↓
     * USER_ROLES
     *   ↓
     * ROLES
     *
     * Même liste de rôles de base que AuthService (voir son commentaire) — priorité au rôle
     * explicite s'il est reconnu, jamais un rôle décoratif choisi au hasard.
     */
    private static final List<String> KNOWN_BASE_ROLES = List.of("admin", "rh", "excelliam", "supervisor", "team_leader", "agent");

    /** Même repli Service → rôle de base que AuthService.SERVICE_CODE_TO_BASE_ROLE — voir son
     *  commentaire. Garde ce champ d'affichage cohérent avec le rôle JWT réellement utilisé
     *  pour les permissions, plutôt qu'un rôle arbitraire quand seul un Service est attribué. */
    private static final java.util.LinkedHashMap<String, String> SERVICE_CODE_TO_BASE_ROLE =
            new java.util.LinkedHashMap<>() {{
                put("RH", "RH");
                put("SUPERVISEUR", "SUPERVISOR");
                put("TEAM_LEADER_INBOUND_VOICE", "TEAM_LEADER");
                put("TEAM_LEADER_INBOUND_MAIL", "TEAM_LEADER");
                put("TEAM_LEADER_OUTBOUND", "TEAM_LEADER");
                put("AGENT_INBOUND", "AGENT");
                put("AGENT_OUTBOUND", "AGENT");
                put("AGENT_INBOUND_MAIL", "AGENT");
                put("AGENT_CIB", "AGENT");
            }};

    private String getPrimaryRole(User user) {

        if (user == null ||
                user.getId() == null) {

            return null;
        }

        List<UserRole> userRoles =
                userRoleRepository
                        .findRolesByUserId(
                                user.getId()
                        );

        if (userRoles != null) {
            for (String baseRole : KNOWN_BASE_ROLES) {
                for (UserRole userRole : userRoles) {
                    if (userRole.getRole() != null && baseRole.equalsIgnoreCase(userRole.getRole().getName())) {
                        return userRole.getRole().getName();
                    }
                }
            }
        }

        List<UserServiceAssignment> assignments =
                userServiceAssignmentRepository.findServicesByUserId(user.getId());
        if (assignments != null) {
            for (String code : SERVICE_CODE_TO_BASE_ROLE.keySet()) {
                for (UserServiceAssignment assignment : assignments) {
                    if (assignment.getService() != null && code.equalsIgnoreCase(assignment.getService().getCode())) {
                        return SERVICE_CODE_TO_BASE_ROLE.get(code);
                    }
                }
            }
        }

        if (userRoles == null || userRoles.isEmpty()) {
            return null;
        }

        UserRole userRole =
                userRoles.get(0);

        if (userRole.getRole() == null) {

            return null;
        }

        return userRole
                .getRole()
                .getName();
    }

    /**
     * Retourne le service (pôle d'activité / équipe) principal de l'utilisateur.
     *
     * Relation :
     *
     * USERS
     *   ↓
     * USER_SERVICES
     *   ↓
     * SERVICES
     *
     * Même ordre de priorité que AuthService.QA_FAMILY_SERVICE_PRIORITY pour la famille Quality
     * Assurance — garde ce champ d'affichage cohérent avec le service JWT réellement utilisé
     * pour les permissions plutôt qu'un résultat arbitraire quand plusieurs services QA sont
     * attribués en même temps (ex. Superviseur QA + Quality Assurance).
     */
    private static final List<String> QA_FAMILY_SERVICE_PRIORITY =
            List.of("SUPERVISEUR_QA", "QUALITY_ASSURANCE", "FORMATEUR", "COMMUNICATION");

    private String getPrimaryService(User user) {

        if (user == null ||
                user.getId() == null) {

            return null;
        }

        List<UserServiceAssignment> assignments =
                userServiceAssignmentRepository
                        .findServicesByUserId(
                                user.getId()
                        );

        if (assignments == null ||
                assignments.isEmpty()) {

            return null;
        }

        for (String code : QA_FAMILY_SERVICE_PRIORITY) {
            for (UserServiceAssignment assignment : assignments) {
                if (assignment.getService() != null && code.equalsIgnoreCase(assignment.getService().getCode())) {
                    return assignment.getService().getName();
                }
            }
        }

        UserServiceAssignment assignment =
                assignments.get(0);

        if (assignment.getService() == null) {

            return null;
        }

        return assignment
                .getService()
                .getName();
    }
}

package com.ecobank.rccportal.config;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * S'exécute une fois au démarrage de l'application :
 * 1. Attribue le rôle admin au matricule configuré (rcc.auth.bootstrap-admin-username),
 *    sans intervention SQL manuelle.
 * 2. Si le mode bypass est actif (voir TestBypassProperties), crée les comptes de
 *    test qui y sont déclarés — idempotent, ne recrée rien s'ils existent déjà.
 */
@Slf4j
@Component
public class DevAccountsBootstrap implements CommandLineRunner {

    @Value("${rcc.auth.bootstrap-admin-username:}")
    private String bootstrapAdminUsername;

    private final TestBypassProperties testBypassProperties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final RccServiceRepository serviceRepository;
    private final UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /** Rattachement agent ↔ agence des comptes de test d'agence (table UserAgency). */
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DevAccountsBootstrap(
            TestBypassProperties testBypassProperties,
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            RccServiceRepository serviceRepository,
            UserServiceAssignmentRepository userServiceAssignmentRepository,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.testBypassProperties = testBypassProperties;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.serviceRepository = serviceRepository;
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {

        if (bootstrapAdminUsername != null && !bootstrapAdminUsername.isBlank()) {
            promoteToAdmin(bootstrapAdminUsername.trim());
        }

        if (testBypassProperties.isEnabled() && testBypassProperties.getAccounts() != null) {
            log.warn("⚠ [TEST BYPASS] Mode actif — {} compte(s) de test seront créés/vérifiés.",
                    testBypassProperties.getAccounts().size());
            for (TestBypassProperties.Account account : testBypassProperties.getAccounts()) {
                ensureTestAccount(account);
            }
        }
    }

    private void promoteToAdmin(String username) {
        userRepository.findFirstByUsernameIgnoreCase(username).ifPresentOrElse(user -> {
            Role adminRole = roleRepository.findByNameIgnoreCase("admin")
                    .orElseGet(() -> roleRepository.save(
                            Role.builder().name("ADMIN").description("Administrateur").build()));

            if (!userRoleRepository.existsByUser_IdAndRole_Id(user.getId(), adminRole.getId())) {
                userRoleRepository.save(UserRole.builder().user(user).role(adminRole).build());
                log.warn("[Bootstrap] Rôle admin attribué automatiquement à {}", username);
            }
        }, () -> log.warn(
                "[Bootstrap] rcc.auth.bootstrap-admin-username={} introuvable en base — ignoré",
                username));
    }

    private void ensureTestAccount(TestBypassProperties.Account account) {
        if (account.getUsername() == null || account.getUsername().isBlank()) {
            return;
        }

        // Un compte EXCELLIAM ne passe JAMAIS par le mode bypass MFA (voir AuthService.
        // initiateLogin) — il a besoin d'un vrai mot de passe hashé en base, comparé via
        // PasswordEncoder, pas du mot de passe en clair "BYPASS" des autres comptes de test.
        boolean isExcelliam = account.getRole() != null && "excelliam".equalsIgnoreCase(account.getRole().trim());

        User user = userRepository.findFirstByUsernameIgnoreCase(account.getUsername())
                .orElseGet(() -> {
                    User created = User.builder()
                            .username(account.getUsername())
                            .name(account.getName())
                            .email(account.getEmail())
                            .password(isExcelliam ? passwordEncoder.encode(account.getPassword()) : "BYPASS") // jamais utilisé pour l'authentification AD/MFA réelle
                            .status("APPROVED")
                            .accountEnabled(true)
                            .accountLocked(false)
                            .accountExpired(false)
                            .credentialsExpired(false)
                            .failedAttempts(0)
                            .build();
                    log.warn("[Bootstrap] Compte de test créé : {}", account.getUsername());
                    return userRepository.save(created);
                });

        // Migration d'un compte EXCELLIAM déjà créé avant ce changement (password="BYPASS",
        // qui ne matchera plus jamais dans AuthService.initiateLogin) — rehashe une fois.
        if (isExcelliam && "BYPASS".equals(user.getPassword())) {
            user.setPassword(passwordEncoder.encode(account.getPassword()));
            userRepository.save(user);
            log.warn("[Bootstrap] Compte EXCELLIAM {} migré vers un mot de passe hashé (plus de bypass MFA).", account.getUsername());
        }

        if (account.getRole() != null && !account.getRole().isBlank()) {
            Role role = roleRepository.findByNameIgnoreCase(account.getRole())
                    .orElseGet(() -> roleRepository.save(Role.builder().name(account.getRole()).build()));
            if (!userRoleRepository.existsByUser_IdAndRole_Id(user.getId(), role.getId())) {
                userRoleRepository.save(UserRole.builder().user(user).role(role).build());
            }
        }

        if (account.getService() != null && !account.getService().isBlank()) {
            RccService service = serviceRepository.findByNameIgnoreCase(account.getService())
                    .orElseGet(() -> serviceRepository.save(RccService.builder()
                            .name(account.getService())
                            .code(account.getService().toUpperCase().replace(" ", "_"))
                            .enabled(true)
                            .displayOrder(0)
                            .openInNewTab(false)
                            .portalApp(true)
                            .status("En service")
                            .build()));
            if (!userServiceAssignmentRepository.existsByUserIdAndServiceId(user.getId(), service.getId())) {
                userServiceAssignmentRepository.save(
                        UserServiceAssignment.builder().user(user).service(service).build());
            }
        }

        if (account.getActivity() != null && !account.getActivity().isBlank()
                && !account.getActivity().equals(user.getActivity())) {
            user.setActivity(account.getActivity());
            userRepository.save(user);
        }

        if (account.getAgencyCode() != null && !account.getAgencyCode().isBlank() && jdbc != null) {
            assignTestAgency(user, account.getAgencyCode().trim().toUpperCase());
        }

        if (account.getLedTeam() != null && !account.getLedTeam().isBlank()
                && !account.getLedTeam().equals(user.getLedTeam())) {
            user.setLedTeam(account.getLedTeam());
            userRepository.save(user);
        }
    }

    /** Rattache un compte de test d'agence à son agence (une seule fois — jamais d'écrasement). */
    private void assignTestAgency(User user, String agencyCode) {
        try {
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.UserAgency WHERE UserId = ?", Integer.class, user.getId());
            if (existing != null && existing > 0) return;
            java.util.List<Long> ids = jdbc.queryForList(
                    "SELECT BranchId FROM dbo.BankBranches WHERE IsActive = 1 AND Name LIKE ? ORDER BY BranchId", Long.class, "%(" + agencyCode + ")");
            if (ids.isEmpty()) {
                log.warn("[Bootstrap] Agence {} introuvable — compte {} non rattaché.", agencyCode, user.getUsername());
                return;
            }
            jdbc.update("INSERT INTO dbo.UserAgency (UserId, BranchId, AssignedBy) VALUES (?, ?, ?)", user.getId(), ids.get(0), "Compte de test");
            log.warn("[Bootstrap] Compte de test {} rattaché à l'agence {}.", user.getUsername(), agencyCode);
        } catch (RuntimeException e) {
            log.warn("[Bootstrap] Rattachement agence de {} impossible pour l'instant : {}", user.getUsername(), e.getMessage());
        }
    }
}

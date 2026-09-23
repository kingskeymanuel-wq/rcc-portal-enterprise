package com.ecobank.rccportal;

import com.ecobank.rccportal.config.TestBypassProperties;
import com.ecobank.rccportal.dto.LoginChallengeResponse;
import com.ecobank.rccportal.dto.RegisterRequest;
import com.ecobank.rccportal.model.Role;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserRole;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.RefreshTokenRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import com.ecobank.rccportal.security.JwtService;
import com.ecobank.rccportal.service.AttendanceService;
import com.ecobank.rccportal.service.AuthGatewayClient;
import com.ecobank.rccportal.service.AuthService;
import com.ecobank.rccportal.service.BestEffortPersistenceService;
import com.ecobank.rccportal.service.ChallengeService;
import com.ecobank.rccportal.service.NotificationService;
import com.ecobank.rccportal.service.ShiftService;
import com.ecobank.rccportal.util.ApiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Couvre le flux d'authentification réel à deux étapes côté UX (mot de passe puis OTP),
 * mais un seul appel technique combiné à l'étape 2 — voir AuthService : initiateLogin()
 * ne vérifie plus rien pour le flux réel (ouvre juste un challenge), completeLogin()
 * appelle AuthGatewayClient.login(username, password, otp) en un seul coup signé+chiffré
 * et résout/provisionne le compte RCC uniquement en cas de succès. Le mode bypass test
 * (TestBypassProperties) est désactivé ici pour exercer le vrai chemin gateway ; un test
 * dédié couvre le bypass activé.
 *
 * ⚠ REFONTE — remplace l'ancienne version de ce test, écrite pour l'architecture
 * AdAuthClient (SOAP rechercherUserAD) + MfaService (XML ecimfa) séparés, abandonnée par
 * Ecobank au profit de la gateway SAGED centralisée (HMAC+AES) — voir AuthGatewayClient
 * et le Javadoc de classe d'AuthService.
 */
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private com.ecobank.rccportal.repository.RoleRepository roleRepository;

    @Mock
    private UserServiceAssignmentRepository userServiceAssignmentRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private ChallengeService challengeService;

    @Mock
    private AuthGatewayClient authGatewayClient;

    @Mock
    private AttendanceService attendanceService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private RccNotificationRepository rccNotificationRepository;

    @Mock
    private BestEffortPersistenceService bestEffortPersistenceService;

    @Mock
    private ShiftService shiftService;

    private TestBypassProperties testBypassProperties;

    private AuthService authService;

    @BeforeEach
    void setUp() {

        MockitoAnnotations.openMocks(this);

        // Bypass désactivé par défaut : les tests ci-dessous exercent le vrai chemin
        // gateway, pas le contournement de développement.
        testBypassProperties = new TestBypassProperties();
        testBypassProperties.setEnabled(false);

        authService = new AuthService(
                userRepository,
                userRoleRepository,
                roleRepository,
                userServiceAssignmentRepository,
                refreshTokenRepository,
                jwtService,
                challengeService,
                authGatewayClient,
                attendanceService,
                notificationService,
                rccNotificationRepository,
                bestEffortPersistenceService,
                testBypassProperties,
                shiftService
        );
    }

    private Role buildRole(Long id, String name) {

        return Role.builder()
                .id(id)
                .name(name)
                .description(name + " RCC")
                .build();
    }

    private User buildApprovedUser(
            Long id,
            String username) {

        return User.builder()
                .id(id)
                .username(username)
                .name("Koné Aïssatou")
                .email(username + "@ecobank.com")
                .accountEnabled(true)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .failedAttempts(0)
                .loginCount(0)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private void mockAgentRole(User user) {

        Role role = buildRole(2L, "AGENT");

        UserRole userRole = UserRole.builder()
                .user(user)
                .role(role)
                .build();

        when(userRoleRepository.findRolesByUserId(user.getId()))
                .thenReturn(List.of(userRole));
    }

    private AuthGatewayClient.GatewayUser gatewayUser(String username) {
        return new AuthGatewayClient.GatewayUser(
                1L, username, "Aïssatou Koné", "ECI",
                List.of("AGENT"), List.of(), List.of());
    }

    // =========================================================
    // ETAPE 1 - PLUS AUCUNE VERIFICATION POUR LE FLUX REEL
    // =========================================================

    /**
     * Le flux réel n'appelle plus rien à l'étape 1 (la gateway exige les 3
     * valeurs ensemble) — initiateLogin() se contente d'ouvrir un challenge,
     * quel que soit le username/password saisis. C'est completeLogin() qui
     * valide tout, voir tests plus bas.
     */
    @Test
    void initiateLoginAlwaysOpensChallengeForRealFlow() {

        when(challengeService.create("kone.aissatou", "login"))
                .thenReturn(new ChallengeService.Created("challenge-123", "000000"));

        LoginChallengeResponse result =
                authService.initiateLogin("kone.aissatou", "password");

        assertEquals("challenge-123", result.challengeId());
        assertTrue(result.twoFactorRequired());
        assertNull(result.maskedEmail()); // identité pas encore connue à ce stade

        verifyNoInteractions(authGatewayClient);
        verifyNoInteractions(userRepository);
    }

    @Test
    void initiateLoginRejectsBlankUsername() {

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.initiateLogin("  ", "password")
                );

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    // =========================================================
    // ETAPE 2 - GATEWAY D'AUTHENTIFICATION (completeLogin)
    // =========================================================

    @Test
    void completeLoginFailsWhenChallengeExpiredOrUnknown() {

        when(challengeService.peek("unknown-challenge", "login")).thenReturn(null);

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.completeLogin("unknown-challenge", "123456")
                );

        assertEquals("invalid_code", exception.getCode());
    }

    @Test
    void completeLoginRejectsInvalidGatewayCredentials() {

        when(challengeService.create("kone.aissatou", "login"))
                .thenReturn(new ChallengeService.Created("challenge-1", "000000"));
        authService.initiateLogin("kone.aissatou", "wrong-password");

        when(challengeService.peek("challenge-1", "login"))
                .thenReturn(new ChallengeService.Consumed("kone.aissatou", "login", "000000"));

        when(authGatewayClient.login("kone.aissatou", "wrong-password", "123456"))
                .thenReturn(new AuthGatewayClient.Result(
                        AuthGatewayClient.Status.INVALID, null, null,
                        "Identifiants incorrects ou code incorrect."));

        when(userRepository.findFirstByUsernameIgnoreCase("kone.aissatou"))
                .thenReturn(Optional.empty()); // pas encore de compte RCC — rien à pénaliser

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.completeLogin("challenge-1", "123456")
                );

        assertEquals("invalid_code", exception.getCode());
    }

    @Test
    void completeLoginSurfacesGatewayUnreachableWithoutConsumingAttempt() {

        when(challengeService.create("kone.aissatou", "login"))
                .thenReturn(new ChallengeService.Created("challenge-2", "000000"));
        authService.initiateLogin("kone.aissatou", "password");

        when(challengeService.peek("challenge-2", "login"))
                .thenReturn(new ChallengeService.Consumed("kone.aissatou", "login", "000000"));

        when(authGatewayClient.login("kone.aissatou", "password", "123456"))
                .thenReturn(new AuthGatewayClient.Result(
                        AuthGatewayClient.Status.UNREACHABLE, null, null,
                        "Service d'authentification indisponible."));

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.completeLogin("challenge-2", "123456")
                );

        assertEquals("auth_gateway_unreachable", exception.getCode());
        // Panne technique — ne doit jamais pénaliser le compte.
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Find-or-CREATE : un utilisateur validé par la gateway mais absent de la base RCC est
     * provisionné automatiquement, SANS AUCUN RÔLE — c'est cette absence de rôle qui bloque
     * la connexion (message "no_role", déjà existant avant cette fonctionnalité — voir
     * assertAccountUsable()), pas le statut PENDING. Un admin doit attribuer un rôle
     * (via Administration > Comptes en attente > Approuver, ou manuellement) avant que le
     * compte soit utilisable. Voir AuthService.provisionUserFromGateway().
     */
    @Test
    void gatewayValidButUnknownInRccIsAutoProvisionedButBlockedUntilRoleAssigned() {

        when(challengeService.create("new.agent", "login"))
                .thenReturn(new ChallengeService.Created("challenge-3", "000000"));
        authService.initiateLogin("new.agent", "password");

        when(challengeService.peek("challenge-3", "login"))
                .thenReturn(new ChallengeService.Consumed("new.agent", "login", "000000"));

        when(authGatewayClient.login("new.agent", "password", "123456"))
                .thenReturn(new AuthGatewayClient.Result(
                        AuthGatewayClient.Status.OK, gatewayUser("new.agent"), "jwt-token", null));

        when(userRepository.findFirstByUsernameIgnoreCase("new.agent"))
                .thenReturn(Optional.empty());

        User created = User.builder()
                .id(42L)
                .username("new.agent")
                .name("Aïssatou Koné")
                .status("PENDING")
                .accountEnabled(true)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .failedAttempts(0)
                .build();
        when(userRepository.save(any(User.class))).thenReturn(created);
        when(userRoleRepository.findRolesByUserId(42L)).thenReturn(List.of());

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.completeLogin("challenge-3", "123456")
                );

        assertEquals("no_role", exception.getCode());
        verify(userRepository).save(any(User.class));
        verify(userRoleRepository, never()).save(any(UserRole.class));
    }

    @Test
    void lockedAccountCannotCompleteLoginEvenWithValidGatewayResponse() {

        User user = buildApprovedUser(1L, "locked.agent");
        user.setAccountLocked(true);
        mockAgentRole(user);

        when(challengeService.create("locked.agent", "login"))
                .thenReturn(new ChallengeService.Created("challenge-4", "000000"));
        authService.initiateLogin("locked.agent", "password");

        when(challengeService.peek("challenge-4", "login"))
                .thenReturn(new ChallengeService.Consumed("locked.agent", "login", "000000"));

        when(authGatewayClient.login("locked.agent", "password", "123456"))
                .thenReturn(new AuthGatewayClient.Result(
                        AuthGatewayClient.Status.OK, gatewayUser("locked.agent"), "jwt-token", null));

        when(userRepository.findFirstByUsernameIgnoreCase("locked.agent"))
                .thenReturn(Optional.of(user));

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.completeLogin("challenge-4", "123456")
                );

        assertEquals("account_locked", exception.getCode());
    }

    @Test
    void disabledAccountCannotCompleteLoginEvenWithValidGatewayResponse() {

        User user = buildApprovedUser(1L, "disabled.agent");
        user.setAccountEnabled(false);
        mockAgentRole(user);

        when(challengeService.create("disabled.agent", "login"))
                .thenReturn(new ChallengeService.Created("challenge-5", "000000"));
        authService.initiateLogin("disabled.agent", "password");

        when(challengeService.peek("challenge-5", "login"))
                .thenReturn(new ChallengeService.Consumed("disabled.agent", "login", "000000"));

        when(authGatewayClient.login("disabled.agent", "password", "123456"))
                .thenReturn(new AuthGatewayClient.Result(
                        AuthGatewayClient.Status.OK, gatewayUser("disabled.agent"), "jwt-token", null));

        when(userRepository.findFirstByUsernameIgnoreCase("disabled.agent"))
                .thenReturn(Optional.of(user));

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.completeLogin("challenge-5", "123456")
                );

        assertEquals("account_disabled", exception.getCode());
    }

    // Remarque : le succès complet de completeLogin() (session JWT émise) est couvert par les
    // tests d'intégration bout-en-bout ; issueSession() dépend de JwtService/RefreshTokenRepository
    // dont le comportement exact n'est pas l'objet de ce test unitaire isolé de AuthService seul.

    // =========================================================
    // REGISTER — toujours redirigé vers l'AD Ecobank
    // =========================================================

    @Test
    void registerAlwaysRedirectsToActiveDirectory() {

        RegisterRequest request =
                new RegisterRequest(
                        "new.agent",
                        "new.agent@ecobank.com",
                        "Inbound",
                        "password123"
                );

        when(userRepository.existsByUsernameIgnoreCase("new.agent"))
                .thenReturn(false);

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.registerAccount(request)
                );

        assertTrue(exception.getMessage().contains("Active Directory"));
    }

    @Test
    void registerRejectsExistingUsernameBeforeRedirectingToAd() {

        RegisterRequest request =
                new RegisterRequest(
                        "existing.agent",
                        "existing.agent@ecobank.com",
                        "Inbound",
                        "password123"
                );

        when(userRepository.existsByUsernameIgnoreCase("existing.agent"))
                .thenReturn(true);

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.registerAccount(request)
                );

        assertEquals("username_taken", exception.getCode());
    }

    // =========================================================
    // PASSWORD RESET — RCC ne modifie jamais le mot de passe AD
    // =========================================================

    @Test
    void forgotPasswordRedirectsToEcobankItProcess() {

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.forgotPassword(
                                "kone.aissatou",
                                "kone.aissatou@ecobank.com"
                        )
                );

        assertTrue(exception.getMessage().contains("Active Directory"));
    }

    @Test
    void passwordResetCannotModifyAdPassword() {

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> authService.confirmPasswordReset(
                                "challenge",
                                "123456",
                                "new-password"
                        )
                );

        assertTrue(exception.getMessage().contains("Active Directory"));
    }
}

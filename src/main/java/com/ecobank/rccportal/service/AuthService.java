package com.ecobank.rccportal.service;

import com.ecobank.rccportal.config.TestBypassProperties;
import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.RccService;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserRole;
import com.ecobank.rccportal.model.UserServiceAssignment;
import com.ecobank.rccportal.repository.RefreshTokenRepository;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import com.ecobank.rccportal.security.JwtService;
import com.ecobank.rccportal.util.ApiException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AuthService — authentification via la gateway SAGED centralisée, à deux
 * étapes côté UX (écran mot de passe puis écran OTP), mais UN SEUL appel
 * technique combiné {username, password, otp} à l'étape 2.
 *
 * ⚠ REFONTE — remplace les anciens appels séparés AdAuthClient (SOAP
 * rechercherUserAD) + MfaService (XML ecimfa), abandonnés par Ecobank au
 * profit d'une gateway centralisée signée (HMAC-SHA512) + chiffrée
 * (AES-256-GCM), déjà en production côté Closed Loop Service/Intérim/
 * Salaire/Virement — voir AuthGatewayClient. Ces deux anciennes classes
 * restent dans le projet (TestAdController s'en sert encore pour du
 * diagnostic manuel) mais ne sont plus utilisées par le flux de connexion.
 *
 * ⚠ Changement de comportement assumé, identique à Closed Loop Service : la
 * gateway exige les 3 valeurs ensemble, donc un mot de passe erroné n'est
 * plus détecté à l'étape 1 mais seulement à l'étape 2 (après saisie de
 * l'OTP) — message générique, ne distingue pas mot de passe invalide et
 * code invalide.
 *
 * ⚠ MODE BYPASS TEST (voir TestBypassProperties, rcc.auth.test-bypass.*) :
 *   quand un username correspond à un compte déclaré dans
 *   rcc.auth.test-bypass.accounts ET que le mode est activé, initiateLogin()
 *   saute complètement l'appel réel à la gateway (vérification par mot de
 *   passe local à la place), et completeLogin() fait de même pour l'OTP
 *   (vérification par rcc.auth.test-bypass.otp-code à la place).
 *   Chaque contournement écrit un WARN explicite dans les logs. Ce mode ne
 *   doit JAMAIS être actif en production.
 *
 * Flux d'authentification (comptes réels, hors bypass) :
 *
 *   initiateLogin(username, password)                    — ÉTAPE 1
 *       │
 *       ▼
 *   AUCUN appel réseau ici — la gateway a besoin des 3 valeurs à la fois
 *       │   (username, password, otp). On se contente de vérifier que les
 *       │   deux champs ne sont pas vides, puis d'ouvrir un challenge OTP :
 *       ▼
 *   ChallengeService.create()            — challenge temporaire ; le password
 *       │                                   est gardé en mémoire (PendingLogin),
 *       │                                   lié au challengeId, avec un compteur
 *       │                                   d'essais OTP. JAMAIS envoyé au
 *       │                                   frontend, JAMAIS persisté en base.
 *       ▼
 *   retour challengeId
 *
 *   completeLogin(challengeId, code)                      — ÉTAPE 2
 *       │
 *       ▼
 *   ChallengeService.peek()              — valide le challenge SANS le détruire
 *       │                                   (plusieurs essais OTP autorisés)
 *       ▼
 *   AuthGatewayClient.login(username, password, otp)
 *       │                                   — appel unique signé+chiffré ; la
 *       │                                     gateway valide identité + mot de
 *       │                                     passe + OTP en une fois
 *       ▼
 *   ┌── succès ────────────────────────────────────────────────────────────┐
 *   │ findOrProvisionUserFromGateway() — FIND-OR-CREATE : si le compte RCC   │
 *   │ n'existe pas encore, il est créé automatiquement (sans rôle, sans      │
 *   │ filiale/service/équipe) — voir provisionUserFromGateway().             │
 *   │ teamAssignmentLocked reste false, donc l'agent est redirigé vers       │
 *   │ l'écran team-setup après approbation admin : il ne peut choisir que    │
 *   │ parmi les filiales/services/équipes déjà configurés (jamais de saisie  │
 *   │ libre). assertAccountUsable() (enabled/locked/rôle) ; challenge        │
 *   │ consommé, pending retiré, failedAttempts remis à 0, issueSession()     │
 *   │ (JWT + refresh token).                                                 │
 *   └───────────────────────────────────────────────────────────────────────┘
 *   ┌── échec ─────────────────────────────────────────────────────────────┐
 *   │ attemptsLeft-- ; s'il reste des essais → challenge + pending PRÉSERVÉS │
 *   │ ; sinon session OTP épuisée → challenge consommé, pending retiré, et   │
 *   │ registerFailedAttempt() si un compte RCC existe déjà pour ce username  │
 *   │ (rebond compte comme un échec ÉTAPE 1).                                │
 *   └───────────────────────────────────────────────────────────────────────┘
 *
 * VERROUILLAGE :
 *   registerFailedAttempt() incrémente user.failedAttempts, alimenté
 *   uniquement par une session OTP épuisée (étape 2) — l'étape 1 ne vérifie
 *   plus rien, voir ci-dessus. Au seuil MAX_STEP1_FAILURES → accountLocked =
 *   true (verrou dur, déverrouillage par un administrateur uniquement, sauf
 *   pour bootstrap-admin-username — voir promoteBootstrapAdminIfNeeded()).
 *   assertAccountUsable() lit accountLocked à chaque tentative complète, donc
 *   un compte verrouillé est refusé dès sa prochaine connexion.
 *
 * ⚠ @Transactional(noRollbackFor = ApiException.class) : ApiException étant
 *   une RuntimeException, un throw annulerait sinon le save() de
 *   registerFailedAttempt() — le compteur ne serait jamais persisté.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
public class AuthService {

    private static final String STATUS_PENDING = "PENDING"; // visibilité admin uniquement — voir provisionUserFromAd()

    /** Même clé que DevAccountsBootstrap — voir provisionUserFromAd() : promotion admin
     *  immédiate si ce compte est créé APRÈS le démarrage (le bootstrap ne s'exécute qu'une
     *  fois, au boot ; un compte inexistant à ce moment-là ne serait sinon jamais promu). */
    @org.springframework.beans.factory.annotation.Value("${rcc.auth.bootstrap-admin-username:}")
    private String bootstrapAdminUsername;

    /** Nombre d'essais OTP autorisés par challenge (étape 2). */
    private static final int MAX_OTP_ATTEMPTS = 3;

    /** Seuil d'échecs étape 1 (mot de passe erroné + OTP épuisé) avant verrou dur. */
    private static final int MAX_STEP1_FAILURES = 2;

    /** Durée de vie du password gardé en mémoire pour un challenge en cours. */
    private static final Duration PENDING_LOGIN_TTL = Duration.ofMinutes(10);

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final com.ecobank.rccportal.repository.RoleRepository roleRepository;
    private final UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    private final JwtService jwtService;
    private final ChallengeService challengeService;
    private final AuthGatewayClient authGatewayClient;

    private final AttendanceService attendanceService;
    private final BestEffortPersistenceService bestEffortPersistenceService;
    private final ShiftService shiftService;
    private final NotificationService notificationService;
    private final RccNotificationRepository rccNotificationRepository;

    private final TestBypassProperties testBypassProperties;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /**
     * Cache mémoire à usage unique : challengeId -> mot de passe saisi
     * + compteur d'essais OTP restants.
     *
     * Nécessaire car AuthGatewayClient.login() attend username+password+otp
     * ensemble en un seul appel, mais le password n'est plus disponible côté
     * serveur au moment de completeLogin() (seul le challengeId transite
     * depuis le frontend entre les deux étapes). Le mot de passe n'est
     * JAMAIS persisté en base — uniquement gardé en mémoire process,
     * supprimé dès consommation ou expiration.
     *
     * ⚠ En cas de redémarrage de l'application entre les deux étapes du
     * login (déploiement, crash), le challenge devient invalide et
     * l'utilisateur doit recommencer — comportement acceptable et sans
     * incidence de sécurité (pas de perte de données persistées).
     */
    private final ConcurrentHashMap<String, PendingLogin> pendingLogins =
            new ConcurrentHashMap<>();

    private record PendingLogin(String username, String password,
                                Instant createdAt, AtomicInteger attemptsLeft) {
        boolean isExpired() {
            return Instant.now().isAfter(createdAt.plus(PENDING_LOGIN_TTL));
        }
    }

    public AuthService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            com.ecobank.rccportal.repository.RoleRepository roleRepository,
            UserServiceAssignmentRepository userServiceAssignmentRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            ChallengeService challengeService,
            AuthGatewayClient authGatewayClient,
            AttendanceService attendanceService,
            NotificationService notificationService,
            RccNotificationRepository rccNotificationRepository,
            BestEffortPersistenceService bestEffortPersistenceService,
            TestBypassProperties testBypassProperties,
            ShiftService shiftService,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.userServiceAssignmentRepository =
                userServiceAssignmentRepository;
        this.refreshTokenRepository = refreshTokenRepository;

        this.jwtService = jwtService;
        this.challengeService = challengeService;
        this.authGatewayClient = authGatewayClient;

        this.attendanceService = attendanceService;
        this.notificationService = notificationService;
        this.rccNotificationRepository = rccNotificationRepository;

        this.bestEffortPersistenceService = bestEffortPersistenceService;
        this.testBypassProperties = testBypassProperties;
        this.shiftService = shiftService;
        this.passwordEncoder = passwordEncoder;
    }

    // =========================================================
    // LOGIN - ETAPE 1
    // =========================================================

    /**
     * Étape 1 (mot de passe) — ne vérifie plus rien auprès de la gateway réelle
     * (celle-ci exige username+password+otp ensemble, voir AuthGatewayClient) ;
     * en mode bypass uniquement, le mot de passe est vérifié localement contre
     * un compte de test (voir TestBypassProperties). Ouvre ensuite un challenge
     * OTP (vérifié à l'étape 2 via AuthGatewayClient, sauf bypass).
     *
     * Aucun mot de passe n'est stocké en base. Il est gardé en mémoire
     * process, lié au challengeId, uniquement le temps nécessaire à
     * l'appel gateway de l'étape 2.
     *
     * noRollbackFor = ApiException.class : garantit que registerFailedAttempt()
     * (mot de passe erroné) est persisté malgré le throw qui suit.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginChallengeResponse initiateLogin(
            String username,
            String password) {

        String normalizedUsername = normalize(username);

        if (normalizedUsername.isBlank()) {
            throw ApiException.badRequest(
                    "Username is required."
            );
        }

        if (password == null || password.isBlank()) {
            throw ApiException.badRequest(
                    "Password is required."
            );
        }

        // -------------------------------------------------
        // ⚠ COMPTE EXCELLIAM — pas de MFA, aucun compte AD Ecobank
        // -------------------------------------------------
        // Le prestataire Excelliam n'existe pas dans l'Active Directory Ecobank : la gateway
        // MFA (conçue pour les employés Ecobank) ne peut donc jamais lui envoyer de code. On
        // authentifie directement ici (mot de passe hashé local, voir User.password) et on
        // ouvre la session tout de suite — jamais de ChallengeService.create() pour ce rôle.
        User excelliamUser = userRepository.findFirstByUsernameIgnoreCase(normalizedUsername).orElse(null);
        if (excelliamUser != null && "excelliam".equalsIgnoreCase(getPrimaryRole(excelliamUser))) {

            // Le statut actif est vérifié EN PREMIER, avant même de révéler si un mot de passe
            // existe déjà ou si celui saisi est correct — un compte Excelliam désactivé par
            // l'admin (exigence sécurité explicite : accès au portail Excelliam uniquement si
            // actif en base) ne doit donner AUCUNE information supplémentaire, ni permettre de
            // créer un mot de passe, ni tenter une connexion.
            assertAccountUsable(excelliamUser);

            if (excelliamUser.getPassword() == null || excelliamUser.getPassword().isBlank()) {
                // Première connexion (ou mot de passe réinitialisé par un admin) — aucun mot de
                // passe local encore défini. Le frontend doit proposer l'écran de création,
                // jamais un message "identifiants incorrects" qui n'aiderait pas l'utilisateur.
                log.info("[EXCELLIAM] Password setup required for username={}", normalizedUsername);
                return new LoginChallengeResponse(null, null, false, null, true);
            }

            if (!passwordEncoder.matches(password, excelliamUser.getPassword())) {
                log.warn("[EXCELLIAM] Invalid password for username={}", normalizedUsername);
                registerFailedAttempt(excelliamUser);
                throw ApiException.unauthorized("invalid_credentials", "Incorrect username or password.");
            }

            log.warn("[EXCELLIAM] Authentication without MFA (username={}) — external provider, not in Ecobank AD", normalizedUsername);

            excelliamUser.setFailedAttempts(0);
            excelliamUser.setAccountLocked(false);
            userRepository.save(excelliamUser);

            SessionTokens tokens = issueSession(excelliamUser, null);
            return new LoginChallengeResponse(null, null, false, tokens, false);
        }

        TestBypassProperties.Account bypassAccount = findBypassAccount(normalizedUsername);

        User user = null;

        if (bypassAccount != null) {

            // -------------------------------------------------
            // ⚠ MODE BYPASS TEST — AUCUN APPEL GATEWAY RÉEL
            // -------------------------------------------------

            if (!password.equals(bypassAccount.getPassword())) {

                log.warn(
                        "[TEST BYPASS] Invalid password for bypass account username={}",
                        normalizedUsername
                );

                userRepository.findFirstByUsernameIgnoreCase(normalizedUsername)
                        .ifPresent(this::registerFailedAttempt);

                throw ApiException.unauthorized(
                        "invalid_credentials",
                        "Incorrect username or password."
                );
            }

            log.warn(
                    "[TEST BYPASS] Authentication gateway skipped (username={})",
                    normalizedUsername
            );

            user = userRepository.findFirstByUsernameIgnoreCase(normalizedUsername)
                    .orElseThrow(() -> ApiException.forbidden(
                            "account_not_registered",
                            "Compte non enregistré. Contactez votre administrateur."
                    ));

            assertAccountUsable(user);

        } else {

            // -------------------------------------------------
            // FLUX RÉEL — gateway d'authentification SAGED centralisée
            // -------------------------------------------------
            // ⚠ REFONTE (alignée sur Closed Loop Service) : la gateway exige
            // {username, password, otp} en un seul appel signé+chiffré — voir
            // AuthGatewayClient. Aucune vérification possible ici avec
            // seulement username+password ; le mot de passe (et l'identité de
            // l'utilisateur) ne sont donc confirmés qu'à l'étape 2, après
            // saisie du code, une fois les 3 valeurs réunies. On se contente
            // ici de mémoriser la tentative et d'ouvrir l'écran OTP.
            log.info(
                    "Pending login créé — popup OTP ouverte (username={})",
                    normalizedUsername
            );
        }

        // -----------------------------------------------------
        // CHALLENGE MFA
        // -----------------------------------------------------

        String challengeUsername = user != null ? user.getUsername() : normalizedUsername;

        ChallengeService.Created created =
                challengeService.create(
                        challengeUsername,
                        "login"
                );

        cleanupExpiredPendingLogins();

        pendingLogins.put(
                created.challengeId(),
                new PendingLogin(
                        challengeUsername,
                        password,
                        Instant.now(),
                        new AtomicInteger(MAX_OTP_ATTEMPTS)
                )
        );

        log.info(
                "Login challenge created (username={}, challengeId={})",
                challengeUsername,
                created.challengeId()
        );

        return new LoginChallengeResponse(
                created.challengeId(),
                user != null ? maskEmail(user.getEmail()) : null,
                true,
                null,
                false
        );
    }

    // =========================================================
    // LOGIN - ETAPE 2
    // =========================================================

    /**
     * Vérifie username+password+otp en un seul appel auprès de la gateway
     * d'authentification SAGED (AuthGatewayClient), sauf en mode bypass où le
     * mot de passe a déjà été vérifié localement à l'étape 1 et où seul le
     * code (rcc.auth.test-bypass.otp-code) est vérifié ici — puis crée la
     * session JWT.
     *
     * Plusieurs essais OTP sont autorisés (MAX_OTP_ATTEMPTS) : le challenge
     * est validé de façon NON destructive (peek) et n'est consommé qu'au
     * succès ou à l'épuisement des essais.
     *
     * noRollbackFor = ApiException.class : garantit que registerFailedAttempt()
     * (session OTP épuisée) est persisté malgré le throw qui suit.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public SessionTokens completeLogin(
            String challengeId,
            String code) {

        // Validation NON destructive : le challenge survit tant qu'il reste
        // des essais.
        ChallengeService.Consumed peeked =
                challengeService.peek(challengeId, "login");

        PendingLogin pending =
                challengeId == null
                        ? null
                        : pendingLogins.get(challengeId);

        if (peeked == null || pending == null || pending.isExpired()) {

            // Nettoyage défensif si l'un des deux subsiste.
            if (challengeId != null) {
                challengeService.consume(challengeId, "login");
                pendingLogins.remove(challengeId);
            }

            log.warn(
                    "Login challenge/pending missing or expired (challengeId={})",
                    challengeId
            );

            throw ApiException.unauthorized(
                    "invalid_code",
                    "Login request expired. Please sign in again."
            );
        }

        if (code == null || code.isBlank()) {
            // Code vide : challenge + pending PRÉSERVÉS, aucun essai décompté.
            throw ApiException.unauthorized(
                    "invalid_code",
                    "Verification code is required."
            );
        }

        // -----------------------------------------------------
        // VERIFICATION — gateway d'authentification SAGED centralisée
        // (username+password+otp en un seul appel), ou bypass test
        // -----------------------------------------------------

        TestBypassProperties.Account bypassAccount = findBypassAccount(pending.username());

        User user;
        boolean otpOk;
        String gatewayAccessToken = null; // capturé uniquement si la connexion passe par la vraie gateway (pas en bypass test)

        if (bypassAccount != null) {

            user = userRepository
                    .findFirstByUsernameIgnoreCase(pending.username())
                    .orElseThrow(() -> ApiException.notFound("Unknown user."));

            assertAccountUsable(user);

            otpOk = testBypassProperties.getOtpCode() != null
                    && testBypassProperties.getOtpCode().equals(code);

            log.warn(
                    "[TEST BYPASS] Gateway verification skipped for username={} (bypass OTP check)",
                    user.getUsername()
            );

        } else {

            // ⚠ REFONTE (alignée sur Closed Loop Service) : appel combiné unique
            // {username, password, otp} — remplace l'ancien mfaService.verifierOtp()
            // séparé. Le mot de passe n'a encore jamais été vérifié à ce stade
            // (voir initiateLogin()) : c'est CET appel qui valide les 3 valeurs
            // à la fois. Message d'erreur volontairement générique en cas
            // d'échec — impossible de distinguer mot de passe invalide et code
            // invalide, comportement assumé identique à Closed Loop Service.
            AuthGatewayClient.Result gatewayResult =
                    authGatewayClient.login(pending.username(), pending.password(), code);

            switch (gatewayResult.status()) {
                case MISCONFIGURED -> {
                    // rcc.auth.gateway.* absent/vide — problème de config, pas de réseau.
                    log.error(
                            "Auth gateway MISCONFIGURED (username={}): {} — vérifier rcc.auth.gateway.login-url/hmac-secret/aes-password",
                            pending.username(),
                            gatewayResult.message()
                    );
                    throw ApiException.unauthorized(
                            "auth_gateway_unreachable",
                            gatewayResult.message()
                    );
                }
                case UNREACHABLE -> {
                    // Panne technique réseau/timeout/HTTP — ne consomme pas une tentative, challenge préservé.
                    log.error(
                            "Auth gateway UNREACHABLE (username={}): {}",
                            pending.username(),
                            gatewayResult.message()
                    );
                    throw ApiException.unauthorized(
                            "auth_gateway_unreachable",
                            gatewayResult.message()
                    );
                }
                default -> { /* INVALID ou OK — traité ci-dessous */ }
            }

            otpOk = gatewayResult.status() == AuthGatewayClient.Status.OK;

            if (otpOk) {
                gatewayAccessToken = gatewayResult.accessToken();
                user = findOrProvisionUserFromGateway(pending.username(), gatewayResult.user());
                assertAccountUsable(user);
            } else {
                // Identité pas encore confirmée — on tente quand même de retrouver un
                // compte RCC existant pour lui appliquer la pénalité d'échec ci-dessous ;
                // s'il n'existe pas encore (tout premier login), rien à pénaliser.
                user = userRepository.findFirstByUsernameIgnoreCase(pending.username()).orElse(null);
            }
        }

        if (!otpOk) {

            int restantes = pending.attemptsLeft().decrementAndGet();

            if (restantes <= 0) {
                // Session OTP épuisée : destruction + pénalité étape 1.
                challengeService.consume(challengeId, "login");
                pendingLogins.remove(challengeId);
                if (user != null) {
                    registerFailedAttempt(user);
                }

                log.warn(
                        "OTP attempts exhausted for username={}",
                        pending.username()
                );

                throw ApiException.unauthorized(
                        "too_many_attempts",
                        "Trop de tentatives. Veuillez vous reconnecter."
                );
            }

            log.warn(
                    "MFA verification failed for username={} ({} attempt(s) left)",
                    pending.username(),
                    restantes
            );

            throw ApiException.unauthorized(
                    "invalid_code",
                    "Code incorrect. Il vous reste " + restantes
                            + " tentative" + (restantes > 1 ? "s" : "") + "."
            );
        }

        // -----------------------------------------------------
        // SUCCÈS : consommation définitive + session
        // -----------------------------------------------------

        challengeService.consume(challengeId, "login");
        pendingLogins.remove(challengeId);

        // Réinitialisation des échecs.
        user.setFailedAttempts(0);
        user.setAccountLocked(false);

        userRepository.save(user);

        // Pointage automatique — isolé dans sa propre transaction (REQUIRES_NEW)
        // pour ne pas empoisonner la transaction principale de completeLogin() si
        // dbo.AttendanceRecords est absente. Voir saveRefreshTokenBestEffort pour
        // l'explication du try/catch côté appelant.
        try {
            bestEffortPersistenceService.clockInBestEffort(user);
        } catch (Exception e) {
            log.warn(
                    "Automatic attendance clock-in (REQUIRES_NEW) failed to commit for {}: {}",
                    user.getUsername(),
                    e.getMessage()
            );
        }

        try {
            shiftService.recordLogin(user);
        } catch (Exception e) {
            log.warn("Shift LOGIN event recording failed for {}: {}", user.getUsername(), e.getMessage());
        }

        return issueSession(user, gatewayAccessToken);
    }

    private void cleanupExpiredPendingLogins() {
        pendingLogins.values().removeIf(PendingLogin::isExpired);
    }

    // =========================================================
    // EXCELLIAM — création du mot de passe à la première connexion
    // =========================================================

    /** Longueur minimale imposée pour un mot de passe Excelliam créé par l'utilisateur —
     *  ces comptes n'ont aucune politique de mot de passe imposée par l'AD Ecobank (ils n'y
     *  sont pas), donc le portail doit appliquer sa propre exigence minimale. */
    private static final int EXCELLIAM_MIN_PASSWORD_LENGTH = 8;

    /**
     * Crée le mot de passe d'un compte EXCELLIAM lors de sa toute première connexion —
     * jamais utilisable pour CHANGER un mot de passe déjà défini (voir le contrôle
     * password == null ci-dessous) : une vraie réinitialisation reste une action admin
     * distincte (désactiver puis réactiver le compte remet le champ à null côté admin —
     * voir requestExcelliamPasswordReset ci-après), jamais un endpoint self-service qui
     * accepterait n'importe qui connaissant juste le username.
     *
     * Le statut actif est vérifié EN PREMIER, avant toute autre chose — même exigence de
     * sécurité que initiateLogin() : un compte désactivé ne doit jamais pouvoir se créer un
     * mot de passe, qu'il en ait déjà un ou non.
     */
    @Transactional
    public void setExcelliamPassword(String username, String newPassword) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername.isBlank()) {
            throw ApiException.badRequest("Username is required.");
        }
        if (newPassword == null || newPassword.length() < EXCELLIAM_MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest(
                    "Le mot de passe doit contenir au moins " + EXCELLIAM_MIN_PASSWORD_LENGTH + " caractères.");
        }

        User user = userRepository.findFirstByUsernameIgnoreCase(normalizedUsername)
                .orElseThrow(() -> ApiException.unauthorized("invalid_credentials", "Incorrect username or password."));

        if (!"excelliam".equalsIgnoreCase(getPrimaryRole(user))) {
            // Message volontairement identique au cas "compte inconnu" — ne jamais révéler
            // qu'un username existe mais n'est pas Excelliam.
            throw ApiException.unauthorized("invalid_credentials", "Incorrect username or password.");
        }

        assertAccountUsable(user);

        if (user.getPassword() != null && !user.getPassword().isBlank()) {
            throw ApiException.forbidden(
                    "password_already_set",
                    "Un mot de passe existe déjà pour ce compte. Contactez un administrateur pour le réinitialiser.");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.warn("[EXCELLIAM] Password created at first login (username={})", normalizedUsername);
    }

    /** Réservé à un administrateur (vérifié côté controller) — remet le mot de passe d'un
     *  compte EXCELLIAM à null, pour que la personne repasse par setExcelliamPassword() à sa
     *  prochaine connexion (mot de passe oublié, ou rotation de sécurité demandée). Ne
     *  fonctionne QUE sur un compte EXCELLIAM — jamais sur un compte AD Ecobank, dont le mot
     *  de passe n'est de toute façon jamais stocké ici. */
    @Transactional
    public void requestExcelliamPasswordReset(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));
        if (!"excelliam".equalsIgnoreCase(getPrimaryRole(user))) {
            throw ApiException.badRequest("Cette action n'est possible que pour un compte Excelliam.");
        }
        user.setPassword(null);
        userRepository.save(user);
        log.warn("[EXCELLIAM] Password reset requested by admin (username={})", user.getUsername());
    }


    // =========================================================
    // MODE BYPASS TEST
    // =========================================================

    /**
     * Retourne la définition du compte de test correspondant à ce username,
     * ou null si le mode bypass est désactivé ou si aucun compte ne correspond.
     */
    private TestBypassProperties.Account findBypassAccount(String username) {

        if (!testBypassProperties.isEnabled()
                || testBypassProperties.getAccounts() == null
                || username == null) {
            return null;
        }

        return testBypassProperties.getAccounts().stream()
                .filter(a -> a.getUsername() != null
                        && a.getUsername().equalsIgnoreCase(username))
                .findFirst()
                .orElse(null);
    }

    // =========================================================
    // RECHERCHE UTILISATEUR (FIND-OR-CREATE) — gateway d'authentification
    // =========================================================

    /**
     * Find-or-CREATE : un compte RCC authentifié avec succès par la gateway réelle
     * (jamais en mode bypass — voir TestBypassProperties) mais absent de la base est
     * provisionné automatiquement, plutôt que rejeté. Le compte créé est volontairement
     * vierge (aucun rôle, aucune filiale/service/équipe) : teamAssignmentLocked reste à
     * false, ce qui le fait atterrir sur l'écran "team-setup" existant (voir
     * WorkflowService.submitTeamAssignment) dès sa première connexion active — un choix
     * obligatoire parmi les filiales/services/équipes DÉJÀ CONFIGURÉS dans le portail,
     * jamais de saisie libre. Ça garantit qu'aucun compte n'entre dans le portail sans
     * org connue, sans pour autant bloquer un agent réel juste parce que personne ne l'a
     * créé à la main au préalable.
     */
    private User findOrProvisionUserFromGateway(
            String requestedUsername,
            AuthGatewayClient.GatewayUser gatewayUser) {

        String username =
                normalize(
                        gatewayUser != null && gatewayUser.username() != null
                                ? gatewayUser.username()
                                : requestedUsername
                );

        User user = userRepository
                .findFirstByUsernameIgnoreCase(username)
                .or(() -> adoptImportedAccount(username, gatewayUser))
                .orElseGet(() -> provisionUserFromGateway(username, gatewayUser));

        // Rattrapage — un compte créé AVANT que la gateway ne transmette l'email (voir
        // AuthGatewayClient.GatewayUser) n'en a jamais eu ; on le complète dès qu'il est
        // disponible, sans jamais écraser un email déjà renseigné.
        if ((user.getEmail() == null || user.getEmail().isBlank())
                && gatewayUser != null && gatewayUser.email() != null && !gatewayUser.email().isBlank()) {
            user.setEmail(gatewayUser.email());
            user = userRepository.save(user);
        }

        promoteBootstrapAdminIfNeeded(user, username);

        return user;
    }

    /**
     * Rattrapage bootstrap-admin — appelé à CHAQUE connexion réelle réussie, pas
     * seulement à la création du compte. Nécessaire car DevAccountsBootstrap ne
     * s'exécute qu'une fois, au démarrage de l'application : un compte qui n'existait
     * pas encore à ce moment-là (cas normal pour un tout premier provisioning) ne
     * serait sinon jamais promu, même après sa création — d'où ce contrôle
     * systématique et idempotent ici (existsByUser_IdAndRole_Id évite tout doublon si
     * déjà admin).
     */
    private void promoteBootstrapAdminIfNeeded(User user, String username) {
        if (bootstrapAdminUsername == null || bootstrapAdminUsername.isBlank()
                || !bootstrapAdminUsername.trim().equalsIgnoreCase(username)) {
            return;
        }

        boolean needsSave = false;

        // Ce compte précis ne doit jamais rester verrouillé/désactivé/en attente — c'est le
        // seul moyen d'entrer dans le portail pour tout configurer au tout début. Un verrou
        // accumulé par des tentatives précédentes (mauvais mot de passe, mauvais OTP, etc.)
        // ne doit pas le bloquer indéfiniment.
        if (Boolean.TRUE.equals(user.getAccountLocked())) {
            user.setAccountLocked(false);
            needsSave = true;
        }
        if (Boolean.FALSE.equals(user.getAccountEnabled())) {
            user.setAccountEnabled(true);
            needsSave = true;
        }
        if (user.getFailedAttempts() != null && user.getFailedAttempts() > 0) {
            user.setFailedAttempts(0);
            needsSave = true;
        }
        if (!"APPROVED".equalsIgnoreCase(user.getStatus())) {
            user.setStatus("APPROVED"); // cohérent avec assertAccountUsable() qui laisse passer ADMIN sans validation
            needsSave = true;
        }
        if (needsSave) {
            userRepository.save(user);
            log.warn("[AUTH PROVISIONING] {} (bootstrap-admin) déverrouillé/réactivé automatiquement.", username);
        }

        com.ecobank.rccportal.model.Role adminRole = roleRepository.findByNameIgnoreCase("admin")
                .orElseGet(() -> roleRepository.save(
                        com.ecobank.rccportal.model.Role.builder().name("ADMIN").description("Administrateur").build()));

        if (userRoleRepository.existsByUser_IdAndRole_Id(user.getId(), adminRole.getId())) {
            return; // déjà admin — rien de plus à faire
        }
        userRoleRepository.save(UserRole.builder().user(user).role(adminRole).build());
        log.warn("[AUTH PROVISIONING] {} correspond à bootstrap-admin-username — promu ADMIN.", username);
    }

    private com.ecobank.rccportal.repository.LoginAuditRepository loginAuditRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLoginAuditRepository(com.ecobank.rccportal.repository.LoginAuditRepository loginAuditRepository) {
        this.loginAuditRepository = loginAuditRepository;
    }

    /**
     * Première connexion Active Directory d'un agent dont le compte a déjà été créé par un import
     * (planning, KPI, roster) sous un autre identifiant : on reprend CE compte (même e-mail, ou nom
     * correspondant sans ambiguïté, et jamais utilisé pour se connecter) au lieu d'en créer un
     * second — c'est ainsi que naissaient les doublons « MOKE CARMELA » / « MOKE Marie Carmella ».
     */
    java.util.Optional<User> adoptImportedAccount(String username, AuthGatewayClient.GatewayUser gatewayUser) {
        if (gatewayUser == null) return java.util.Optional.empty();
        User candidate = null;
        if (gatewayUser.email() != null && !gatewayUser.email().isBlank()) {
            candidate = userRepository.findByEmailIgnoreCase(gatewayUser.email().trim()).orElse(null);
        }
        if (candidate == null && gatewayUser.fullName() != null && !gatewayUser.fullName().isBlank()) {
            candidate = com.ecobank.rccportal.util.PersonNames.findUnique(gatewayUser.fullName(), userRepository.findAll(), User::getName);
        }
        if (candidate == null || candidate.getUsername().equalsIgnoreCase(username)) return java.util.Optional.empty();
        if (loginAuditRepository != null && loginAuditRepository.existsByUser_Id(candidate.getId())) {
            return java.util.Optional.empty(); // compte déjà utilisé par quelqu'un : on ne le reprend pas
        }
        log.warn("[AUTH PROVISIONING] Compte importé {} repris par la connexion AD {} (même personne : {}).",
                candidate.getUsername(), username, candidate.getName());
        candidate.setUsername(username);
        if ((candidate.getEmail() == null || candidate.getEmail().isBlank()) && gatewayUser.email() != null) candidate.setEmail(gatewayUser.email());
        return java.util.Optional.of(userRepository.save(candidate));
    }

    private User provisionUserFromGateway(String username, AuthGatewayClient.GatewayUser gatewayUser) {
        String fullName = gatewayUser != null && gatewayUser.fullName() != null && !gatewayUser.fullName().isBlank()
                ? gatewayUser.fullName()
                : username;

        User created = User.builder()
                .username(username)
                .name(fullName)
                .email(gatewayUser != null ? gatewayUser.email() : null) // désormais transmis par la gateway (voir AuthGatewayClient.GatewayUser)
                .password("GATEWAY") // jamais utilisé pour l'authentification — la gateway est la seule source de vérité
                .status(STATUS_PENDING) // visibilité dans Administration > Comptes en attente — ce n'est pas ce champ qui bloque la connexion, voir plus bas
                .accountEnabled(true)
                .accountLocked(false)
                .accountExpired(false)
                .credentialsExpired(false)
                .failedAttempts(0)
                .teamAssignmentLocked(false) // force le passage par team-setup après l'approbation admin
                .build();
        created = userRepository.save(created);

        // Volontairement AUCUN rôle attribué ici. C'est précisément l'absence de rôle qui
        // bloque la connexion (voir assertAccountUsable() — le contrôle "no_role" existait
        // déjà avant cette fonctionnalité, pour un tout autre cas). Attribuer un rôle par
        // défaut ici rendrait ce blocage inopérant : le compte serait immédiatement
        // utilisable sans validation admin, ce qui va à l'encontre du besoin ("aucun compte
        // ne doit passer sans approbation IT"). Le rôle est attribué par UserService.approve()
        // (AGENT par défaut) ou manuellement par un admin via Administration > Utilisateurs —
        // les deux débloquent la connexion.
        log.warn("[AUTH PROVISIONING] Nouveau compte auto-créé depuis la gateway (username={}) — sans rôle, en attente d'approbation admin.", username);
        return created;
    }

    // =========================================================
    // VERROUILLAGE (ÉTAPE 1)
    // =========================================================

    /**
     * Échec d'authentification étape 1 (mot de passe AD erroné, ou session OTP
     * entièrement épuisée qui rebondit vers l'étape 1). Au-delà du seuil, verrou
     * dur — déverrouillage par un administrateur uniquement.
     */
    private void registerFailedAttempt(User user) {

        int failed =
                (user.getFailedAttempts() == null
                        ? 0
                        : user.getFailedAttempts()) + 1;

        user.setFailedAttempts(failed);

        if (failed >= MAX_STEP1_FAILURES) {
            user.setAccountLocked(true);
            log.warn(
                    "Account locked after {} failed attempt(s) (username={})",
                    failed,
                    user.getUsername()
            );
            alertItAdmins(user.getUsername(), "Compte verrouillé",
                    "Le compte " + user.getUsername() + " (" + (user.getName() != null ? user.getName() : "nom inconnu") +
                    ") vient d'être verrouillé après " + failed + " échecs de connexion.",
                    "UNLOCK_ACCOUNT", user.getUsername());
        }

        userRepository.save(user);
    }

    // =========================================================
    // ACCOUNT STATUS
    // =========================================================

    private void assertAccountUsable(User user) {

        if (user == null) {

            throw ApiException.unauthorized(
                    "Authentication required."
            );
        }

        if (Boolean.FALSE.equals(
                user.getAccountEnabled()
        )) {

            throw ApiException.forbidden(
                    "account_disabled",
                    "This account is disabled."
            );
        }

        if (Boolean.TRUE.equals(
                user.getAccountLocked()
        )) {

            throw ApiException.locked(
                    "account_locked",
                    "This account is locked."
            );
        }


        if (Boolean.TRUE.equals(
                user.getAccountExpired()
        )) {

            throw ApiException.forbidden(
                    "account_expired",
                    "This account has expired."
            );
        }

        String role =
                getPrimaryRole(user);

        // Un compte ADMIN (déjà approuvé, sinon bloqué plus haut) n'a pas besoin d'un
        // rôle "métier" supplémentaire pour se connecter.
        if ("ADMIN".equalsIgnoreCase(role)) {
            return;
        }

        // Compte reconnu (matricule/username existant) mais sans aucun rôle attribué —
        // ne doit pas pouvoir se connecter. L'IT doit d'abord lui assigner un rôle.
        if (role == null || role.isBlank()) {
            throw ApiException.forbidden(
                    "no_role",
                    "Votre compte existe mais n'a pas encore de rôle attribué. Contactez le support IT."
            );
        }

    }

    // =========================================================
    // ROLES
    // =========================================================

    /**
     * Rôles "de base" reconnus par TOUT le système de permissions du portail (comparaison
     * insensible à la casse partout : "admin".equalsIgnoreCase(requester.role()), etc.).
     * Un utilisateur peut avoir PLUSIEURS rôles en base (dbo.ROLES/USER_ROLES) — certains
     * décoratifs/informatifs (ex. "Formateur", "Team Leader Inbound Voice", "Head RCC
     * (Superviseur)") qui ne correspondent à AUCUNE de ces chaînes exactes. Si un tel rôle
     * décoratif était choisi comme rôle JWT, l'utilisateur perdrait l'accès à tout le portail
     * (chaque vérification de permission échouerait) — d'où cette priorité stricte, dans
     * l'ordre du plus large accès au plus restreint.
     */
    private static final List<String> KNOWN_BASE_ROLES = List.of("admin", "rh", "excelliam", "supervisor", "team_leader", "agent");

    /**
     * Codes de service (dbo.SERVICES.CODE, voir WorkflowSchemaBootstrap) qui doivent, à défaut
     * de Rôle de base explicite (dbo.ROLES), suffire à eux seuls à déterminer le rôle JWT — donc
     * l'accès au portail. Sans ce repli, un utilisateur à qui l'admin n'a attribué QUE le
     * service "RH"/"Superviseur"/"Team Leader ..." (sans lui donner en plus le Rôle du même nom)
     * se retrouverait bloqué à la connexion ("no_role", voir plus haut) alors que
     * l'attribution du service seul est précisément le geste attendu (voir AdministrationService
     * .assignService()). QUALITY_ASSURANCE est volontairement absent d'ici : le profil QA est
     * déjà déterminé séparément à partir du Service (voir getPrimaryService() / computeProfile()
     * côté client), jamais du rôle JWT.
     */
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
                put("AGENCE_CAISSIER", "AGENCE");
                put("AGENCE_GESTIONNAIRE", "AGENCE");
                put("AGENCE", "AGENCE");
            }};

    private String getPrimaryRole(User user) {

        if (user == null ||
                user.getId() == null) {

            return null;
        }

        List<UserRole> roles =
                userRoleRepository
                        .findRolesByUserId(
                                user.getId()
                        );

        // Cherche d'abord un rôle de base connu, dans l'ordre de priorité — jamais un rôle
        // décoratif choisi au hasard qui bloquerait tout accès au portail.
        if (roles != null) {
            for (String baseRole : KNOWN_BASE_ROLES) {
                for (UserRole userRole : roles) {
                    if (userRole.getRole() != null && baseRole.equalsIgnoreCase(userRole.getRole().getName())) {
                        return userRole.getRole().getName();
                    }
                }
            }
        }

        // Aucun Rôle de base trouvé — repli sur les Services attribués (voir
        // SERVICE_CODE_TO_BASE_ROLE ci-dessus), avant le repli dégradé plus bas.
        List<UserServiceAssignment> services =
                userServiceAssignmentRepository.findServicesByUserId(user.getId());
        if (services != null) {
            for (String code : SERVICE_CODE_TO_BASE_ROLE.keySet()) {
                for (UserServiceAssignment assignment : services) {
                    if (assignment.getService() != null && code.equalsIgnoreCase(assignment.getService().getCode())) {
                        return SERVICE_CODE_TO_BASE_ROLE.get(code);
                    }
                }
            }
        }

        if (roles == null || roles.isEmpty()) {
            return null;
        }

        // Aucun rôle de base ni service reconnu parmi ceux attribués — repli sur le premier
        // rôle, comme avant ce correctif (comportement dégradé mais pas pire qu'auparavant).
        UserRole userRole =
                roles.get(0);

        if (userRole.getRole() == null) {
            return null;
        }

        return userRole
                .getRole()
                .getName();
    }

    // =========================================================
    // SERVICES
    // =========================================================

    /**
     * Codes de la famille Quality Assurance, dans l'ordre de priorité à utiliser comme service
     * "principal" (JWT `service`, donc requester.service()) quand plusieurs sont attribués en
     * même temps — ex. un Superviseur QA qui porte aussi le service Quality Assurance de base
     * (voir AdministrationService.assignService(), qui l'attribue automatiquement). Sans cet
     * ordre, `services.get(0)` renverrait un résultat arbitraire (ordre d'attribution), ce qui
     * ferait échouer de façon imprévisible les vérifications "quality assurance" utilisées dans
     * une trentaine de contrôleurs (QualityEvaluationController, TrainingApiController...).
     */
    private static final List<String> QA_FAMILY_SERVICE_PRIORITY =
            List.of("SUPERVISEUR_QA", "QUALITY_ASSURANCE", "FORMATEUR", "COMMUNICATION");

    private String getPrimaryService(User user) {

        if (user == null ||
                user.getId() == null) {

            return null;
        }

        List<UserServiceAssignment> services =
                userServiceAssignmentRepository
                        .findServicesByUserId(
                                user.getId()
                        );

        if (services == null ||
                services.isEmpty()) {

            return null;
        }

        for (String code : QA_FAMILY_SERVICE_PRIORITY) {
            for (UserServiceAssignment assignment : services) {
                if (assignment.getService() != null && code.equalsIgnoreCase(assignment.getService().getCode())) {
                    return code;
                }
            }
        }

        UserServiceAssignment assignment =
                services.get(0);

        RccService service =
                assignment.getService();

        if (service == null) {
            return null;
        }

        if (service.getCode() != null &&
                !service.getCode().isBlank()) {

            return service.getCode();
        }

        return service.getName();
    }

    // =========================================================
    // JWT / SESSION
    // =========================================================

    private SessionTokens issueSession(User user, String gatewayAccessToken) {

        String role =
                getPrimaryRole(user);

        String service =
                getPrimaryService(user);

        if (role == null ||
                role.isBlank()) {

            role = "USER";
        }

        String accessToken =
                jwtService.generateAccessToken(
                        user,
                        role,
                        service
                );

        JwtService.GeneratedRefreshToken refresh =
                jwtService.generateRefreshToken(
                        user
                );

        com.ecobank.rccportal.model.RefreshToken entity =
                com.ecobank.rccportal.model.RefreshToken
                        .builder()
                        .jti(
                                refresh.jti()
                        )
                        .user(user)
                        .expiresAt(
                                LocalDateTime.ofInstant(
                                        refresh.expiresAt(),
                                        ZoneOffset.UTC
                                )
                        )
                        .gatewayAccessToken(gatewayAccessToken)
                        .build();

        // ⚠ TEMPORAIRE : la table dbo.RefreshTokens n'existe pas encore sur
        // certaines bases (schéma non appliqué). Persisté dans une transaction
        // séparée (REQUIRES_NEW) pour ne pas polluer la transaction principale
        // de completeLogin(). Le commit de cette transaction séparée peut lever
        // UnexpectedRollbackException si le save() a échoué (comportement JPA
        // normal) — on catche ICI côté appelant, car cette exception est levée
        // par le proxy Spring au retour de l'appel, pas depuis l'intérieur de
        // la méthode appelée. À retirer ce try/catch une fois le schéma corrigé.
        try {
            bestEffortPersistenceService.saveRefreshTokenBestEffort(entity);
        } catch (Exception e) {
            log.warn(
                    "Refresh token persistence (REQUIRES_NEW) failed to commit for {}: {}",
                    user.getUsername(),
                    e.getMessage()
            );
        }

        return new SessionTokens(
                accessToken,
                refresh.token(),
                toUserResponse(
                        user,
                        role,
                        service
                )
        );
    }

    // =========================================================
    // REFRESH TOKEN
    // =========================================================

    @Transactional
    public SessionTokens refresh(
            String refreshTokenValue) {

        if (refreshTokenValue == null ||
                refreshTokenValue.isBlank()) {

            throw ApiException.unauthorized(
                    "Missing refresh token."
            );
        }

        var claims =
                jwtService.parseClaims(
                        refreshTokenValue
                );

        if (claims.getId() == null) {

            throw ApiException.unauthorized(
                    "Invalid refresh token."
            );
        }

        UUID jti;

        try {

            jti =
                    UUID.fromString(
                            claims.getId()
                    );

        } catch (IllegalArgumentException e) {

            throw ApiException.unauthorized(
                    "Invalid refresh token."
            );
        }

        var stored =
                refreshTokenRepository
                        .findByJti(jti)
                        .orElseThrow(
                                () -> ApiException.unauthorized(
                                        "Refresh token has been revoked or expired."
                                )
                        );

        if (stored.getExpiresAt()
                .isBefore(
                        LocalDateTime.now()
                )) {

            refreshTokenRepository.delete(
                    stored
            );

            throw ApiException.unauthorized(
                    "Refresh token has been revoked or expired."
            );
        }

        // Rotation du refresh token.
        refreshTokenRepository.delete(
                stored
        );

        User user =
                userRepository
                        .findFirstByUsernameIgnoreCase(
                                claims.getSubject()
                        )
                        .orElseThrow(
                                () -> ApiException.unauthorized(
                                        "Unknown user."
                                )
                        );

        assertAccountUsable(user);

        return issueSession(user, null); // rotation de jeton portail — pas de nouveau login gateway
    }

    // =========================================================
    // LOGOUT
    // =========================================================

    @Transactional
    public void logout(String refreshTokenValue) {
        logout(refreshTokenValue, null, null);
    }

    /**
     * Déconnexion. L'identité vient du cookie de session (principal) — le frontend n'envoie
     * pas l'en-tête Refresh-Token : avant ce correctif, logout(null) sortait immédiatement et
     * ni le pointage de sortie ni l'heure de déconnexion n'étaient jamais enregistrés.
     */
    @Transactional
    public void logout(String refreshTokenValue, String principalUsername, String principalRole) {
        String username = principalUsername;
        String role = principalRole;

        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            try {
                var claims = jwtService.parseClaims(refreshTokenValue);
                if (claims.getId() != null) {
                    UUID jti = UUID.fromString(claims.getId());
                    // Déconnexion best-effort côté gateway SAGED, si ce jeton portail avait
                    // été émis suite à un vrai login gateway (voir AuthService.issueSession()).
                    // Ne bloque jamais la déconnexion locale, même en cas d'échec.
                    refreshTokenRepository.findByJti(jti).ifPresent(stored -> {
                        String gatewayToken = stored.getGatewayAccessToken();
                        if (gatewayToken != null && !gatewayToken.isBlank()) {
                            try {
                                authGatewayClient.logout(gatewayToken);
                            } catch (Exception e) {
                                log.warn("Gateway logout best-effort failed: {}", e.getMessage());
                            }
                        }
                    });
                    refreshTokenRepository.deleteByJti(jti);
                }
                if (username == null) {
                    username = claims.getSubject();
                    role = claims.get("role", String.class);
                }
            } catch (Exception e) {
                // Déconnexion idempotente : un token expiré ne doit pas bloquer le logout.
                log.debug("Logout with invalid/expired refresh token.");
            }
        }

        if (username == null || username.isBlank()) return;

        if (!"ADMIN".equalsIgnoreCase(role)) {
            try {
                attendanceService.clockOut(username);
            } catch (Exception e) {
                log.warn("Attendance clock-out failed for {}: {}", username, e.getMessage());
            }
        }

        // Heure de déconnexion retenue dans le suivi de shift (transaction séparée) : à la
        // reconnexion le même jour, le minuteur reprend en continuité et l'absence est tracée.
        try {
            shiftService.recordLogout(username);
        } catch (Exception e) {
            log.warn("Shift LOGOUT event recording failed for {}: {}", username, e.getMessage());
        }
    }

    // =========================================================
    // REGISTER
    // =========================================================

    /**
     * Le frontend historique possède encore /register.
     *
     * Dans le mode Active Directory, RCC ne crée aucun compte : l'accès passe
     * exclusivement par un compte RCC préexistant (find-or-fail).
     */
    @Transactional
    public void registerAccount(
            RegisterRequest request) {

        if (request == null) {

            throw ApiException.badRequest(
                    "Registration request is required."
            );
        }

        /*
         * Le DTO historique utilise "username".
         * Cette valeur correspond maintenant à USERS.USERNAME.
         */
        String username =
                normalize(
                        request.matricule()
                );

        if (username.isBlank()) {

            throw ApiException.badRequest(
                    "Username is required."
            );
        }

        if (userRepository
                .existsByUsernameIgnoreCase(
                        username
                )) {

            throw ApiException.conflict(
                    "username_taken",
                    "This username is already registered."
            );
        }

        throw ApiException.badRequest(
                "Veuillez vous connecter avec votre compte Active Directory Ecobank. "
                        + "Contactez votre administrateur pour l'enregistrement du compte RCC."
        );
    }

    // =========================================================
    // FORGOT PASSWORD
    // =========================================================

    /**
     * RCC ne peut pas modifier le mot de passe Active Directory.
     */
    @Transactional
    public LoginChallengeResponse forgotPassword(
            String username,
            String email) {

        alertItAdmins(username, "Mot de passe oublié",
                "L'utilisateur " + (username != null && !username.isBlank() ? username : "(non identifié)") +
                " a cliqué sur « Mot de passe oublié » sur le portail RCC. Rappel : le portail ne peut pas " +
                "réinitialiser un mot de passe Active Directory — ce doit être traité via le processus IT habituel.");

        throw ApiException.badRequest(
                "La réinitialisation du mot de passe Active Directory "
                        + "doit être effectuée via le processus IT Ecobank. L'équipe IT a été notifiée."
        );
    }

    /**
     * RCC ne modifie jamais le mot de passe AD.
     */
    @Transactional
    public void confirmPasswordReset(
            String challengeId,
            String code,
            String password) {

        throw ApiException.badRequest(
                "Le portail RCC ne peut pas modifier votre mot de passe Active Directory."
        );
    }

    /**
     * Alerte l'équipe IT (rôle ADMIN) — notification en app pour chaque admin, plus e-mail
     * en best-effort (jamais bloquant : si le SMTP n'est pas configuré, l'action déclenchante
     * de l'utilisateur — mot de passe oublié, contact support — doit quand même aboutir).
     * Version sans action associée (notification purement informative).
     */
    @Transactional
    public int alertItAdmins(String username, String subject, String message) {
        return alertItAdmins(username, subject, message, null, null);
    }

    /**
     * Même principe, avec une action directement exécutable depuis la notification (ex.
     * "UNLOCK_ACCOUNT" / matricule du compte verrouillé) — le frontend affiche alors un
     * bouton d'action au lieu d'un simple message à lire.
     */
    @Transactional
    public int alertItAdmins(String username, String subject, String message, String actionType, String actionTarget) {
        List<UserRole> adminRoles = userRoleRepository.findByRoleNameIgnoreCase("admin");
        List<User> admins = adminRoles.stream()
                .map(UserRole::getUser)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        for (User admin : admins) {
            rccNotificationRepository.save(RccNotification.builder()
                    .targetUser(admin)
                    .content("[" + subject + "] " + message)
                    .isRead(false)
                    .actionType(actionType)
                    .actionTarget(actionTarget)
                    .build());
        }

        try {
            java.util.Set<String> emails = new java.util.LinkedHashSet<>(admins.stream()
                    .map(User::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .toList());
            // Destinataire garanti — reçoit toujours l'alerte, même si aucun admin n'a d'e-mail renseigné en base.
            emails.add("edoudou@ecobank.com");
            notificationService.sendBroadcastEmail(new java.util.ArrayList<>(emails), "RCC Portal — " + subject, message);
        } catch (ApiException e) {
            log.warn("Alerte IT : e-mail non envoyé (SMTP non configuré), notification en app conservée : {}", e.getMessage());
        }

        return admins.size();
    }

    // =========================================================
    // USER RESPONSE
    // =========================================================

    private UserResponse toUserResponse(User user, String role, String service) {
        Integer id = user.getId() != null ? user.getId().intValue() : null;
        Boolean active = Boolean.TRUE.equals(user.getAccountEnabled());

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
                null,
                user.getResidencePlace(),
                user.getLedTeam()
        );
    }

    // =========================================================
    // UTILITAIRES
    // =========================================================

    private static String normalize(
            String value) {

        return value == null
                ? ""
                : value
                .trim()
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private static String maskEmail(
            String email) {

        if (email == null ||
                email.isBlank()) {

            return "";
        }

        String[] parts =
                email.split(
                        "@",
                        2
                );

        String local = parts[0];

        String domain =
                parts.length > 1
                        ? parts[1]
                        : "";

        String prefix;

        if (local.length() <= 2) {
            prefix = local;
        } else {
            prefix = local.substring(0, 2);
        }

        return prefix
                + "***@"
                + domain;
    }

    // =========================================================
    // SESSION
    // =========================================================

    public record SessionTokens(
            String accessToken,
            String refreshToken,
            UserResponse user) {
    }
}
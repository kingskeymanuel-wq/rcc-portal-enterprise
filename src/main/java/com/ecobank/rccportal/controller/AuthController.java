package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.LoginChallengeResponse;
import com.ecobank.rccportal.dto.LoginRequest;
import com.ecobank.rccportal.dto.MfaRequest;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.security.JwtService;
import com.ecobank.rccportal.security.SessionCookies;
import com.ecobank.rccportal.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    /**
     * Accessible depuis la page de connexion (pas encore authentifié). RCC ne peut pas
     * réinitialiser un mot de passe Active Directory — cet appel notifie l'équipe IT
     * (en app + e-mail best-effort) et renvoie une confirmation claire à l'utilisateur,
     * plutôt que de faire échouer la requête côté frontend.
     */
    @PostMapping("/forgot-password")
    public java.util.Map<String, String> forgotPassword(@RequestBody java.util.Map<String, String> body) {
        try {
            authService.forgotPassword(body.get("username"), body.get("email"));
        } catch (com.ecobank.rccportal.util.ApiException e) {
            return java.util.Map.of("message", e.getMessage());
        }
        return java.util.Map.of("message", "L'équipe IT a été notifiée.");
    }

    /** Accessible depuis la page de connexion — "Contactez le support IT" (ex. après verrouillage). */
    @PostMapping("/contact-it")
    public java.util.Map<String, Object> contactIt(@RequestBody java.util.Map<String, String> body) {
        String username = body.get("username");
        String reason = body.getOrDefault("message", "L'utilisateur demande de l'aide pour se connecter.");
        int notified = authService.alertItAdmins(username, "Demande d'assistance connexion",
                "Demande depuis la page de connexion" + (username != null && !username.isBlank() ? " (compte : " + username + ")" : "") +
                " : " + reason);
        return java.util.Map.of("message", "L'équipe IT a été notifiée.", "adminsNotified", notified);
    }


    /**
     * Etape 1 : Active Directory (ou session posée directement ici pour un compte de test,
     * sans MFA — voir AuthService.initiateLogin et le champ "session" de LoginChallengeResponse).
     */
    @PostMapping("/login")
    public LoginChallengeResponse login(@RequestBody LoginRequest request, HttpServletResponse response) {
        LoginChallengeResponse result = authService.initiateLogin(request.username(), request.password());
        if (!result.twoFactorRequired() && result.session() != null) {
            setSessionCookies(response, result.session());
        }
        return result;
    }

    /**
     * Etape 2 : MFA — pose le cookie HttpOnly ici, une fois la session vraiment créée.
     * Le corps JSON renvoie quand même les tokens (le frontend garde le refreshToken
     * en localStorage pour l'appel de déconnexion), mais l'accessToken lui-même n'a
     * plus besoin d'être lu/stocké côté client : c'est le cookie qui fait foi.
     */
    @PostMapping("/mfa")
    public AuthService.SessionTokens validateOtp(@RequestBody MfaRequest request,
                                                 HttpServletResponse response) {
        AuthService.SessionTokens tokens = authService.completeLogin(request.challengeId(), request.otp());
        setSessionCookies(response, tokens);
        return tokens;
    }

    /**
     * Qui est connecté — utilisé par le frontend (session.js) pour savoir quoi
     * afficher, puisque le cookie JWT est HttpOnly (invisible en JavaScript).
     * Renvoie null si personne n'est connecté (pas d'erreur).
     */
    @GetMapping("/me")
    public AuthenticatedUser me(@AuthenticationPrincipal AuthenticatedUser user) {
        return user;
    }

    /**
     * Déconnexion — supprime aussi le cookie côté navigateur.
     */
    @PostMapping("/logout")
    public void logout(@RequestHeader(value = "Refresh-Token", required = false) String refreshToken,
                       @AuthenticationPrincipal AuthenticatedUser user,
                       jakarta.servlet.http.HttpServletRequest request,
                       HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) refreshToken = SessionCookies.read(request, SessionCookies.REFRESH);
        authService.logout(refreshToken, user != null ? user.username() : null, user != null ? user.role() : null);
        SessionCookies.clear(response);
    }

    /**
     * Maintien de session : appelé toutes les quelques minutes par chaque page ouverte (session.js).
     * Le filtre JWT renouvelle le jeton au passage — la session ne tombe jamais pour inactivité.
     */
    @GetMapping("/keepalive")
    public java.util.Map<String, Object> keepalive(@AuthenticationPrincipal AuthenticatedUser user) {
        return java.util.Map.of("active", user != null, "at", java.time.Instant.now().toString());
    }

    /** Jeton d'accès + jeton de renouvellement, gardés tant que la session n'est pas fermée (voir SessionCookies). */
    private void setSessionCookies(HttpServletResponse response, AuthService.SessionTokens tokens) {
        int maxAge = jwtService.refreshMaxAgeSeconds();
        SessionCookies.write(response, SessionCookies.ACCESS, tokens.accessToken(), maxAge);
        if (tokens.refreshToken() != null) SessionCookies.write(response, SessionCookies.REFRESH, tokens.refreshToken(), maxAge);
    }
}

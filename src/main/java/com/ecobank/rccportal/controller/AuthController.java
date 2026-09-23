package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.LoginChallengeResponse;
import com.ecobank.rccportal.dto.LoginRequest;
import com.ecobank.rccportal.dto.MfaRequest;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Doit correspondre exactement au nom lu par JwtAuthenticationFilter. */
    private static final String ACCESS_COOKIE = "eco_access_token";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
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
     * Création du mot de passe d'un compte EXCELLIAM à sa toute première connexion — accessible
     * depuis la page de connexion (pas encore authentifié), comme /forgot-password et
     * /contact-it. Voir AuthService.setExcelliamPassword pour les règles de sécurité (statut
     * actif requis, ne fonctionne qu'une seule fois par compte).
     */
    @PostMapping("/excelliam/set-password")
    public java.util.Map<String, String> setExcelliamPassword(@RequestBody java.util.Map<String, String> body) {
        authService.setExcelliamPassword(body.get("username"), body.get("newPassword"));
        return java.util.Map.of("message", "Mot de passe créé. Vous pouvez maintenant vous connecter.");
    }

    /**
     * Etape 1 : Active Directory (ou session posée directement ici pour un compte EXCELLIAM,
     * sans MFA — voir AuthService.initiateLogin et le champ "session" de LoginChallengeResponse).
     */
    @PostMapping("/login")
    public LoginChallengeResponse login(@RequestBody LoginRequest request, HttpServletResponse response) {
        LoginChallengeResponse result = authService.initiateLogin(request.username(), request.password());
        if (!result.twoFactorRequired() && result.session() != null) {
            setAccessCookie(response, result.session().accessToken());
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
        setAccessCookie(response, tokens.accessToken());
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
                       HttpServletResponse response) {
        authService.logout(refreshToken, user != null ? user.username() : null, user != null ? user.role() : null);
        clearAccessCookie(response);
    }

    private void setAccessCookie(HttpServletResponse response, String accessToken) {
        Cookie cookie = new Cookie(ACCESS_COOKIE, accessToken);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60); // 1h — aligné sur rcc.auth.jwt-access-expires-in-minutes
        // cookie.setSecure(true); // à activer dès que le portail est servi en HTTPS (pas le cas en local http://localhost)
        response.addCookie(cookie);
    }

    private void clearAccessCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(ACCESS_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
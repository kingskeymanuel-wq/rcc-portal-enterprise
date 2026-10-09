package com.ecobank.rccportal.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Filtre d'authentification JWT du portail RCC.
 *
 * Le token peut être récupéré depuis :
 * 1. le cookie HttpOnly "eco_access_token"
 * 2. l'en-tête Authorization: Bearer <token>
 *
 * Nouveau modèle RCC :
 *
 * JWT subject = USERS.USERNAME
 * role        = ROLES
 * service     = SERVICES
 * name        = USERS.NAME
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String LIVE_COUNTRY = "rcc.liveCountry";


    private static final String ACCESS_COOKIE = SessionCookies.ACCESS;
    /** Au-delà, le jeton est ré-émis à la prochaine requête (session glissante). */
    private static final long RENEW_AFTER_MS = 5 * 60 * 1000L;
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    /** Accès réel relu en base : un changement fait dans Administration s'applique sans reconnexion. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AccessResolver accessResolver;

    /** Journal en base de chaque modification d'administration réussie (qui, quoi, état relu en base). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ecobank.rccportal.service.AdminChangeJournal adminChangeJournal;

    /** Fin de shift : la session d'un agent dont le shift est terminé est fermée (voir shiftEndedSince). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.ecobank.rccportal.service.ShiftService shiftService;

    /** Cookie lisible par la page de connexion : « votre shift est terminé, vous avez été déconnecté ». */
    public static final String SESSION_END_COOKIE = "rcc_session_end";

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /** Session ouverte avant la fin du dernier shift de l'agent (fin de shift ou clôture automatique) ? */
    private boolean shiftEndedSince(Claims claims, String username) {
        if (shiftService == null || claims.getIssuedAt() == null) return false;
        java.time.LocalDateTime end = shiftService.shiftEndedAt(username);
        if (end == null) return false;
        java.time.Instant endAt = end.atZone(java.time.ZoneId.systemDefault()).toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return claims.getIssuedAt().toInstant().isBefore(endAt);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null && !token.isBlank()) {

            try {

                JwtService.Parsed parsed = jwtService.parseLenient(token);
                if (parsed == null) throw new IllegalArgumentException("invalid token");
                Claims claims = parsed.claims();
                boolean renew;
                if (parsed.expired()) {
                    // Jeton d'accès échu (poste en veille, page fermée la nuit…) : repris par le jeton de
                    // renouvellement du même utilisateur, sans demander de se reconnecter.
                    JwtService.Parsed refresh = jwtService.parseLenient(SessionCookies.read(request, SessionCookies.REFRESH));
                    if (refresh == null || refresh.expired() || claims.getSubject() == null
                            || !claims.getSubject().equalsIgnoreCase(refresh.claims().getSubject())) {
                        throw new IllegalArgumentException("session expired");
                    }
                    renew = true;
                } else {
                    java.util.Date iat = claims.getIssuedAt();
                    renew = iat == null || System.currentTimeMillis() - iat.getTime() > RENEW_AFTER_MS;
                }

                String username =
                        claims.getSubject();

                String role =
                        claims.get("role", String.class);

                String service =
                        claims.get("service", String.class);

                /*
                 * Compatibilité temporaire avec les anciens JWT
                 * qui contiennent encore "team".
                 */
                if (service == null || service.isBlank()) {
                    service =
                            claims.get("team", String.class);
                }

                String name =
                        claims.get("name", String.class);

                boolean shiftEnded = false;
                if (username != null && !username.isBlank() && accessResolver != null) {
                    try {
                        AccessResolver.Access live = accessResolver.resolve(username);
                        if (live != null) {
                            if (!live.enabled()) {
                                // Compte désactivé par l'administrateur ou le RH : plus aucun accès, tout de suite.
                                SecurityContextHolder.clearContext();
                                filterChain.doFilter(request, response);
                                return;
                            }
                            role = live.role() != null ? live.role() : role;
                            service = live.service();
                            request.setAttribute(LIVE_COUNTRY, live.country());
                            if ("AGENT".equalsIgnoreCase(role) && shiftEndedSince(claims, username)) {
                                // Shift terminé (« Fin de shift », ou clôture automatique à la fin prévue + débordement) :
                                // l'agent est déconnecté ; son prochain shift commence quand il clique sur « Se connecter »,
                                // selon son planning (voir ShiftService.recordLogin).
                                SecurityContextHolder.clearContext();
                                SessionCookies.clear(response);
                                jakarta.servlet.http.Cookie end = new jakarta.servlet.http.Cookie(SESSION_END_COOKIE, "shift");
                                end.setPath("/");
                                end.setMaxAge(15 * 60);
                                response.addCookie(end);
                                shiftEnded = true;
                            }
                        }
                    } catch (RuntimeException dbUnavailable) {
                        // base indisponible : on garde le rôle du jeton
                    }
                }
                if (shiftEnded) throw new IllegalArgumentException("shift ended"); // requête non authentifiée

                if (username != null && !username.isBlank() && renew) {
                    // Session glissante : chaque activité (y compris le maintien automatique de la page) prolonge
                    // la session — personne n'est déconnecté pour être resté longtemps sans cliquer.
                    SessionCookies.write(response, SessionCookies.ACCESS, jwtService.renewAccessToken(claims, role, service),
                            jwtService.refreshMaxAgeSeconds());
                }

                if (username != null && !username.isBlank()) {

                    AuthenticatedUser user =
                            new AuthenticatedUser(
                                    username,
                                    role,
                                    service,
                                    name
                            );

                    List<org.springframework.security.core.GrantedAuthority>
                            authorities;

                    if (role != null && !role.isBlank()) {

                        String authority =
                                role.toUpperCase()
                                        .startsWith("ROLE_")
                                        ? role.toUpperCase()
                                        : "ROLE_" + role.toUpperCase();

                        authorities =
                                List.of(() -> authority);

                    } else {

                        authorities = List.of();
                    }

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    user,
                                    null,
                                    authorities
                            );

                    SecurityContextHolder
                            .getContext()
                            .setAuthentication(authentication);
                }

            } catch (JwtException |
                     IllegalArgumentException exception) {

                /*
                 * Token invalide, expiré ou mal formé.
                 *
                 * On ne considère pas la requête comme authentifiée.
                 * Spring Security décidera ensuite si la route
                 * demandée nécessite une authentification.
                 */
                SecurityContextHolder.clearContext();
            }
        }

        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        // Filiale choisie dans l'en-tête du portail (RCC ECI / RCC ETG) — Superviseur, RH, administrateur, Head QA.
        com.ecobank.rccportal.util.Filiale.set(principal instanceof AuthenticatedUser au0 ? au0 : null,
                request.getHeader(com.ecobank.rccportal.util.Filiale.HEADER));
        // Tout autre compte (Team Leader, agent, QA, formateur…) reste dans SA filiale : les collaborateurs du Togo
        // (RCC ETG) ne sont jamais mélangés à ceux de Côte d'Ivoire (RCC ECI), et inversement.
        if (principal instanceof AuthenticatedUser au1 && !com.ecobank.rccportal.util.Filiale.canSwitch(au1)
                && request.getAttribute(LIVE_COUNTRY) instanceof String own) {
            com.ecobank.rccportal.util.Filiale.setOwn(own);
        }
        try {
            filterChain.doFilter(
                    request,
                    response
            );
        } finally {
            com.ecobank.rccportal.util.Filiale.clear();
        }

        if (adminChangeJournal != null && response.getStatus() < 400
                && com.ecobank.rccportal.service.AdminChangeJournal.isAdministrationChange(request.getMethod(), request.getRequestURI())) {
            adminChangeJournal.record(principal instanceof AuthenticatedUser au ? au.username() : null,
                    request.getMethod(), request.getRequestURI());
        }

        // Action d'administration réussie (rôles, services, équipe menée, activation, membres d'équipe, sorties RH) :
        // l'accès des personnes concernées est relu tout de suite, sur tous les portails.
        if (accessResolver != null && !"GET".equalsIgnoreCase(request.getMethod()) && response.getStatus() < 400) {
            String path = request.getRequestURI();
            if (path.startsWith("/api/admin/") || path.startsWith("/api/users") || path.startsWith("/api/hr/")
                    || path.startsWith("/api/team-leader/members") || path.startsWith("/api/tab-permissions")
                    || path.startsWith("/api/teams") || path.startsWith("/api/services")) {
                accessResolver.evictAll();
            }
        }
    }

    /**
     * Recherche d'abord le JWT dans le cookie HttpOnly.
     *
     * Si aucun cookie n'est présent, utilise ensuite
     * Authorization: Bearer <token>.
     */
    private String extractToken(
            HttpServletRequest request) {

        if (request.getCookies() != null) {

            for (Cookie cookie : request.getCookies()) {

                if (ACCESS_COOKIE.equals(
                        cookie.getName())) {

                    String value =
                            cookie.getValue();

                    if (value != null &&
                            !value.isBlank()) {

                        return value;
                    }
                }
            }
        }

        String authorization =
                request.getHeader("Authorization");

        if (authorization != null &&
                authorization.startsWith(BEARER_PREFIX)) {

            String token =
                    authorization.substring(
                            BEARER_PREFIX.length()
                    ).trim();

            if (!token.isBlank()) {
                return token;
            }
        }

        return null;
    }
}
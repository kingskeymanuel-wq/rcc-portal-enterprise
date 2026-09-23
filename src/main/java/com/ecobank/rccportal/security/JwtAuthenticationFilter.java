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

    private static final String ACCESS_COOKIE = "eco_access_token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
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

                Claims claims =
                        jwtService.parseClaims(token);

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

        filterChain.doFilter(
                request,
                response
        );
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
package com.ecobank.rccportal.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cookies de session du portail (HttpOnly) : jeton d'accès et jeton de renouvellement.
 * Les deux vivent aussi longtemps que le renouvellement (30 jours par défaut) : une page restée ouverte
 * toute la journée, ou un poste sorti de veille, retrouve sa session sans nouvelle connexion.
 * Seule la déconnexion (bouton, fin de shift) ou la désactivation du compte y met fin.
 */
public final class SessionCookies {

    public static final String ACCESS = "eco_access_token";
    public static final String REFRESH = "eco_refresh_token";

    private SessionCookies() {}

    public static void write(HttpServletResponse response, String name, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(name, value == null ? "" : value);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        // cookie.setSecure(true); // à activer dès que le portail est servi en HTTPS
        response.addCookie(cookie);
    }

    public static void clear(HttpServletResponse response) {
        write(response, ACCESS, "", 0);
        write(response, REFRESH, "", 0);
    }

    public static String read(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) return c.getValue();
        }
        return null;
    }
}

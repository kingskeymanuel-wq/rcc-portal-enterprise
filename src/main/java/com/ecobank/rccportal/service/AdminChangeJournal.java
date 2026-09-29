package com.ecobank.rccportal.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Journal des modifications d'administration, écrit en base (dbo.ActionAuditLog, action « ADMINISTRATION »).
 * Appelé après chaque modification RÉUSSIE (voir JwtAuthenticationFilter) : pour une personne, l'état relu
 * en base juste après (rôles, services, équipe, équipe menée, compte) est enregistré — preuve que le
 * changement fait dans Administration est bien dans la base, et historique de qui a fait quoi.
 */
@Service
@lombok.extern.slf4j.Slf4j
public class AdminChangeJournal {

    public static final String ACTION = "ADMINISTRATION";

    private static final Pattern USER_PATH = Pattern.compile("^/api/(?:admin/)?users/(\\d+)(/.*)?$");

    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    public AdminChangeJournal(JdbcTemplate jdbc, AuditLogService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Chemins dont une modification réussie est journalisée. */
    public static boolean isAdministrationChange(String method, String path) {
        if (method == null || path == null || "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) return false;
        if (path.endsWith("/sessions/me") || path.startsWith("/api/users/me")) return false;
        return path.startsWith("/api/admin/") || path.startsWith("/api/users") || path.startsWith("/api/tab-permissions")
                || path.startsWith("/api/teams") || path.startsWith("/api/services") || path.startsWith("/api/site-settings")
                || path.startsWith("/api/sla-rules") || path.startsWith("/api/login-feature-cards");
    }

    static String label(String method, String rest) {
        String m = method.toUpperCase();
        String r = rest == null ? "" : rest;
        if (r.startsWith("/roles")) return m.equals("DELETE") ? "Rôle retiré" : "Rôle ajouté";
        if (r.startsWith("/services")) return m.equals("DELETE") ? "Service retiré" : "Service ajouté";
        if (r.startsWith("/access")) return "Accès modifié";
        if (r.startsWith("/permissions")) return m.equals("DELETE") ? "Permission remise par défaut" : "Permission modifiée";
        if (r.startsWith("/enable")) return "Compte activé";
        if (r.startsWith("/disable")) return "Compte désactivé";
        if (r.startsWith("/hr-fields")) return "Informations RH modifiées";
        if (r.startsWith("/sessions")) return "Connexions fermées";
        if (r.startsWith("/photo")) return "Photo modifiée";
        if (r.isEmpty()) return m.equals("DELETE") ? "Compte supprimé" : "Fiche modifiée";
        return m + " " + r;
    }

    /** N'échoue jamais : un souci de journal ne doit pas faire échouer la modification déjà enregistrée. */
    public void record(String username, String method, String path) {
        try {
            audit.record(username, ACTION, describe(method, path));
        } catch (RuntimeException e) {
            log.warn("[ADMIN] Journal non écrit pour {} {} : {}", method, path, e.getMessage());
        }
    }

    String describe(String method, String path) {
        Matcher m = USER_PATH.matcher(path);
        if (!m.matches()) return method.toUpperCase() + " " + path;
        long id = Long.parseLong(m.group(1));
        String what = label(method, m.group(2));
        List<java.util.Map<String, Object>> u = jdbc.queryForList(
                "SELECT NAME, USERNAME, ACTIVITY, LED_TEAM, ACCOUNT_ENABLED FROM dbo.USERS WHERE ID = ?", id);
        if (u.isEmpty()) return what + " — compte #" + id + " (n'existe plus en base)";
        var row = u.get(0);
        String roles = String.join(", ", jdbc.queryForList(
                "SELECT r.NAME FROM dbo.USER_ROLES ur JOIN dbo.ROLES r ON r.ID = ur.ROLES_ID WHERE ur.USERS_ID = ? ORDER BY r.NAME", String.class, id));
        String services = String.join(", ", jdbc.queryForList(
                "SELECT s.NAME FROM dbo.USER_SERVICES us JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID WHERE us.USER_ID = ? ORDER BY s.NAME", String.class, id));
        Object enabled = row.get("ACCOUNT_ENABLED");
        boolean active = enabled == null || (enabled instanceof Boolean b ? b : ((Number) enabled).intValue() != 0);
        String text = what + " — " + row.get("NAME") + " (" + row.get("USERNAME") + ") → en base : rôles [" + (roles.isEmpty() ? "aucun" : roles)
                + "], services [" + (services.isEmpty() ? "aucun" : services) + "], équipe " + (row.get("ACTIVITY") == null ? "—" : row.get("ACTIVITY"))
                + ", équipe menée " + (row.get("LED_TEAM") == null ? "—" : row.get("LED_TEAM")) + ", compte " + (active ? "actif" : "désactivé");
        return text.length() > 1990 ? text.substring(0, 1990) + "…" : text;
    }
}

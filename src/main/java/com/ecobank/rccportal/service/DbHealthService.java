package com.ecobank.rccportal.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Administration — contrôle en direct que le portail écrit bien dans la base de données :
 * base et compte SQL réellement utilisés, droits sur chaque table, test d'écriture réel (annulé aussitôt),
 * déclencheurs qui pourraient défaire une modification, et données qui faussent les accès (liaisons en
 * double ou orphelines, « équipe menée » invalide). Chaque point dit OK ou exactement ce qui bloque.
 */
@Service
public class DbHealthService {

    /** status : OK, INFO, ATTENTION, A_CORRIGER (action proposée), KO (bloque l'écriture). */
    public record Check(String label, String status, String detail, String action) {
        Check(String label, String status, String detail) { this(label, status, detail, null); }
    }

    public record Report(String server, String database, String sqlLogin, List<Check> checks, boolean ok) {}

    static final List<String> TABLES = List.of("USERS", "USER_ROLES", "USER_SERVICES", "ROLES", "SERVICES",
            "ActionAuditLogs", "AgentSchedules", "DATA_PATCHES");

    static final Set<String> VALID_LED_TEAMS = Set.of("INBOUND_VOICE", "INBOUND_MAIL", "TCHAT", "RAFIKI", "CIB", "OUTBOUND");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DbHealthService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public Report check(String adminUsername) {
        List<Check> checks = new ArrayList<>();
        Map<String, Object> id = jdbc.queryForMap("SELECT @@SERVERNAME AS S, DB_NAME() AS D, SUSER_SNAME() AS L");
        String server = String.valueOf(id.get("S")), db = String.valueOf(id.get("D")), login = String.valueOf(id.get("L"));
        checks.add(new Check("Base utilisée par le portail", "OK",
                "Serveur " + server + ", base " + db + ", compte SQL " + login + ". C'est cette base qu'il faut ouvrir dans SQL Server pour voir les modifications."));

        // Droits du compte SQL du portail sur chaque table.
        for (String t : TABLES) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE name = ? AND schema_id = SCHEMA_ID('dbo')", Integer.class, t);
            if (exists == null || exists == 0) {
                checks.add(new Check("Table dbo." + t, t.equals("DATA_PATCHES") ? "INFO" : "KO", "Table absente de la base " + db + "."));
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String p : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                Integer has = jdbc.queryForObject("SELECT HAS_PERMS_BY_NAME(?, 'OBJECT', ?)", Integer.class, "dbo." + t, p);
                if (has == null || has == 0) missing.add(p);
            }
            checks.add(missing.isEmpty()
                    ? new Check("Droits sur dbo." + t, "OK", "Lecture, ajout, modification et suppression autorisés.")
                    : new Check("Droits sur dbo." + t, "KO", "Le compte SQL " + login + " n'a pas le droit " + String.join(", ", missing)
                    + " : les modifications de l'Administration sur cette table sont refusées par SQL Server. À accorder par le DBA (GRANT "
                    + String.join(", ", missing) + " ON dbo." + t + " TO [" + login + "])."));
        }

        // Déclencheurs qui pourraient modifier ou annuler une écriture.
        List<Map<String, Object>> triggers = jdbc.queryForList("""
                SELECT tr.name AS N, OBJECT_NAME(tr.parent_id) AS T, tr.is_disabled AS D FROM sys.triggers tr
                WHERE tr.parent_id IN (OBJECT_ID('dbo.USERS'), OBJECT_ID('dbo.USER_ROLES'), OBJECT_ID('dbo.USER_SERVICES'),
                                       OBJECT_ID('dbo.ROLES'), OBJECT_ID('dbo.SERVICES'))""");
        checks.add(triggers.isEmpty()
                ? new Check("Déclencheurs (triggers)", "OK", "Aucun déclencheur ne modifie ces tables derrière le portail.")
                : new Check("Déclencheurs (triggers)", "ATTENTION", triggers.stream()
                .map(r -> r.get("N") + " sur dbo." + r.get("T") + (Boolean.TRUE.equals(r.get("D")) ? " (désactivé)" : " (actif)"))
                .reduce((a, b) -> a + ", " + b).orElse("") + " — un déclencheur actif peut modifier ou annuler ce que fait l'Administration."));

        // Test d'écriture réel sur les tables des comptes, annulé aussitôt (aucune trace en base).
        checks.add(writeTest(adminUsername));

        // Données qui faussent les accès.
        Integer dupRoles = jdbc.queryForObject("SELECT COUNT(*) - COUNT(DISTINCT CONCAT(USERS_ID, '-', ROLES_ID)) FROM dbo.USER_ROLES", Integer.class);
        Integer dupSvc = jdbc.queryForObject("SELECT COUNT(*) - COUNT(DISTINCT CONCAT(USER_ID, '-', SERVICE_ID)) FROM dbo.USER_SERVICES", Integer.class);
        Integer orphans = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM dbo.USER_ROLES WHERE USERS_ID NOT IN (SELECT ID FROM dbo.USERS) OR ROLES_ID NOT IN (SELECT ID FROM dbo.ROLES))
                     + (SELECT COUNT(*) FROM dbo.USER_SERVICES WHERE USER_ID NOT IN (SELECT ID FROM dbo.USERS) OR SERVICE_ID NOT IN (SELECT ID FROM dbo.SERVICES))""", Integer.class);
        int bad = (dupRoles == null ? 0 : dupRoles) + (dupSvc == null ? 0 : dupSvc) + (orphans == null ? 0 : orphans);
        checks.add(bad == 0
                ? new Check("Liaisons rôles / services", "OK", "Aucun doublon, aucune liaison vers un compte, rôle ou service supprimé.")
                : new Check("Liaisons rôles / services", "A_CORRIGER", (dupRoles + dupSvc) + " doublon(s) et " + orphans
                + " liaison(s) orpheline(s). Bouton « Corriger » ou « Nettoyer doublons » dans l'éditeur de tables.", "clean-links"));

        List<Map<String, Object>> badLed = jdbc.queryForList(
                "SELECT NAME, USERNAME, LED_TEAM FROM dbo.USERS WHERE LED_TEAM IS NOT NULL AND LTRIM(RTRIM(LED_TEAM)) <> '' AND UPPER(LTRIM(RTRIM(LED_TEAM))) NOT IN ('INBOUND_VOICE','INBOUND_MAIL','TCHAT','RAFIKI','CIB','OUTBOUND')");
        checks.add(badLed.isEmpty()
                ? new Check("Équipe menée (LED_TEAM)", "OK", "Toutes les valeurs sont des équipes valides.")
                : new Check("Équipe menée (LED_TEAM)", "A_CORRIGER", badLed.size() + " compte(s) avec une équipe menée invalide, qui les rend Team Leader sans équipe : "
                + badLed.stream().map(r -> r.get("NAME") + " (" + r.get("USERNAME") + ") = « " + r.get("LED_TEAM") + " »").reduce((a, b) -> a + ", " + b).orElse("")
                + ". « Corriger » vide ce champ.", "clear-led-team"));

        List<Map<String, Object>> last = jdbc.queryForList(
                "SELECT TOP 1 CreatedAt AS W, Username AS U, Details AS T FROM dbo.ActionAuditLogs WHERE Action = ? ORDER BY CreatedAt DESC", AdminChangeJournal.ACTION);
        checks.add(last.isEmpty()
                ? new Check("Dernière modification enregistrée", "INFO", "Aucune modification d'administration journalisée pour l'instant.")
                : new Check("Dernière modification enregistrée", "OK", last.get(0).get("W") + " par " + last.get(0).get("U") + " — " + last.get(0).get("T")));

        boolean ok = checks.stream().noneMatch(c -> c.status().equals("KO"));
        return new Report(server, db, login, checks, ok);
    }

    private Check writeTest(String adminUsername) {
        try {
            String result = tx.execute(status -> {
                status.setRollbackOnly(); // toujours annulé : rien ne reste en base
                Long userId = jdbc.queryForList("SELECT TOP 1 ID FROM dbo.USERS WHERE LOWER(USERNAME) = LOWER(?)", Long.class, adminUsername)
                        .stream().findFirst().orElse(null);
                Long roleId = jdbc.queryForList("SELECT TOP 1 ID FROM dbo.ROLES ORDER BY ID", Long.class).stream().findFirst().orElse(null);
                Long serviceId = jdbc.queryForList("SELECT TOP 1 ID FROM dbo.SERVICES ORDER BY ID", Long.class).stream().findFirst().orElse(null);
                if (userId == null || roleId == null || serviceId == null) return "Test impossible : compte administrateur, rôle ou service introuvable.";
                jdbc.update("UPDATE dbo.USERS SET NAME = NAME WHERE ID = ?", userId);
                Long ur = jdbc.queryForObject("SET NOCOUNT ON; INSERT INTO dbo.USER_ROLES (USERS_ID, ROLES_ID) VALUES (?, ?); SELECT CAST(SCOPE_IDENTITY() AS BIGINT)", Long.class, userId, roleId);
                Long us = jdbc.queryForObject("SET NOCOUNT ON; INSERT INTO dbo.USER_SERVICES (USER_ID, SERVICE_ID) VALUES (?, ?); SELECT CAST(SCOPE_IDENTITY() AS BIGINT)", Long.class, userId, serviceId);
                Integer back = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM dbo.USER_ROLES WHERE ID = ?) + (SELECT COUNT(*) FROM dbo.USER_SERVICES WHERE ID = ?)", Integer.class, ur, us);
                jdbc.update("DELETE FROM dbo.USER_ROLES WHERE ID = ?", ur);
                jdbc.update("DELETE FROM dbo.USER_SERVICES WHERE ID = ?", us);
                return back != null && back == 2 ? null : "Écriture acceptée mais non relue (" + back + "/2).";
            });
            return result == null
                    ? new Check("Test d'écriture réel", "OK", "Modification d'un compte, ajout puis retrait d'un rôle et d'un service : acceptés et relus par SQL Server (test annulé, rien n'est resté).")
                    : new Check("Test d'écriture réel", "KO", result);
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            return new Check("Test d'écriture réel", "KO", "SQL Server refuse l'écriture : " + root.getMessage());
        }
    }

    /** Vide les « équipes menées » invalides (ex. « QA ») : ces comptes cessent d'être Team Leader par erreur. */
    public int clearInvalidLedTeams() {
        return jdbc.update("UPDATE dbo.USERS SET LED_TEAM = NULL WHERE LED_TEAM IS NOT NULL AND (LTRIM(RTRIM(LED_TEAM)) = '' OR UPPER(LTRIM(RTRIM(LED_TEAM))) NOT IN ('INBOUND_VOICE','INBOUND_MAIL','TCHAT','RAFIKI','CIB','OUTBOUND'))");
    }
}

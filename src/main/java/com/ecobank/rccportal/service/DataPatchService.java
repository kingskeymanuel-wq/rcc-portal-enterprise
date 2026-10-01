package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.ScheduleImportResult;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.YearMonth;
import java.util.*;

/**
 * Correctifs de données demandés par l'administration, écrits directement en base :
 * <ul>
 *   <li>appliqués automatiquement UNE fois au démarrage (voir DataPatchBootstrap), suivis dans dbo.DATA_PATCHES
 *       (date, résultat détaillé) — jamais rejoués ensuite, pour ne pas écraser ce que l'administrateur a changé depuis ;</li>
 *   <li>relançables à la main depuis Administration (« Réappliquer »), chaque exécution étant aussi tracée
 *       dans le journal d'audit.</li>
 * </ul>
 */
@Service
@lombok.extern.slf4j.Slf4j
public class DataPatchService {

    public record Patch(String code, String title, String description) {}

    public record PatchStatus(String code, String title, String description, String appliedAt, String appliedBy, String result) {}

    /** Planning Team Inbound Voix d'octobre 2026, agents Inbound Voix, Team Leaders, libellé « Réseaux sociaux ». */
    public static final String INBOUND_VOIX_2026_10 = "INBOUND_VOIX_2026_10";

    /** TEAM_ASSIGNMENT_LOCKED à True pour tous les comptes, et True par défaut en base pour les comptes à venir. */
    public static final String TEAM_ASSIGNMENT_LOCKED_TRUE = "TEAM_ASSIGNMENT_LOCKED_TRUE";

    /** Rôle « Réseaux sociaux » / « Tchat » déjà attribué : service Agent Réseaux sociaux (portail ex-Tchat). */
    public static final String RESEAUX_SOCIAUX_ROLES = "RESEAUX_SOCIAUX_ROLES";

    /** Rôles et services d'agent alignés (Inbound Voix, Inbound Mail, Réseaux sociaux, Rafiki, CIB, Outbound, Télévente). */
    public static final String AGENT_ROLES_SERVICES_SYNC = "AGENT_ROLES_SERVICES_SYNC";

    /** Pôle Inbound Mail : LOUM Olivia Team Leader, comptes de test (bypass) Team Leader et agent Inbound Mail alignés. */
    public static final String INBOUND_MAIL_LEADERSHIP = "INBOUND_MAIL_LEADERSHIP";

    public static final List<Patch> PATCHES = List.of(new Patch(INBOUND_VOIX_2026_10,
            "Team Inbound Voix — planning d'octobre 2026 et rôles",
            "Planning d'octobre 2026 des 32 agents Inbound Voix (fichier Exceliam) ; chacun reçoit le rôle « Agent Inbound Voice », "
                    + "le service Agent Inbound et l'équipe Inbound Voix, sans aucun accès Team Leader. Team Leaders : KOUAO Noël et "
                    + "FOUANGOUP Angèle (Inbound Voix), LOUM Olivia (Inbound Mail). Le canal Tchat devient « Réseaux sociaux »."),
            new Patch(TEAM_ASSIGNMENT_LOCKED_TRUE, "TEAM_ASSIGNMENT_LOCKED = True pour tous",
                    "Met la colonne TEAM_ASSIGNMENT_LOCKED de dbo.USERS à True sur tous les comptes, et sa valeur par défaut en base "
                            + "à True : tout nouveau compte (portail, import, connexion AD, ou ajout direct en SQL) est créé à True."),
            new Patch(RESEAUX_SOCIAUX_ROLES, "Rôle Réseaux sociaux → portail Réseaux sociaux (ex-Tchat)",
                    "Toute personne ayant un rôle « Réseaux sociaux » ou « Tchat » (hors Team Leader) reçoit le service Agent Réseaux sociaux : "
                            + "elle arrive sur le portail Réseaux sociaux (l'ancien portail Tchat) à sa prochaine connexion."),
            new Patch(AGENT_ROLES_SERVICES_SYNC, "Rôles et services d'agent synchronisés",
                    "Chaque rôle d'agent (Inbound Voix, Inbound Mail, Réseaux sociaux, Rafiki, CIB, Outbound, Télévente) reçoit son service "
                            + "et chaque service d'agent son rôle, sans rien retirer — ensuite, choisir l'un applique l'autre automatiquement."),
            new Patch(INBOUND_MAIL_LEADERSHIP, "Inbound Mail — Team Leader LOUM Olivia et accès bypass",
                    "LOUM Olivia devient Team Leader Inbound Mail (service, rôle, équipe menée). Les comptes de test (bypass) "
                            + "teamleader.inboundmail et agent.mail sont alignés : portail Team Leader Inbound Mail et portail agent Inbound Mail."));

    static final String PLANNING_FILE = "data/planning/inbound-voix-2026-10.csv";
    static final String AGENT_ROLE = "Agent Inbound Voice";

    /** Team Leaders désignés : nom, équipe menée, rôle. */
    static final List<String[]> TEAM_LEADERS = List.of(
            new String[]{"KOUAO NOEL", "INBOUND_VOICE", "Team Leader Inbound Voice"},
            new String[]{"FOUANGOUP ANGELE", "INBOUND_VOICE", "Team Leader Inbound Voice"},
            new String[]{"LOUM OLIVIA", "INBOUND_MAIL", "Team Leader Inbound Mail"});

    private final JdbcTemplate jdbc;
    private final ScheduleService scheduleService;
    private final AdministrationService administrationService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public DataPatchService(JdbcTemplate jdbc, ScheduleService scheduleService, AdministrationService administrationService,
                            UserRepository userRepository, AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.scheduleService = scheduleService;
        this.administrationService = administrationService;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    public void ensureTable() {
        jdbc.execute("""
                IF OBJECT_ID('dbo.DATA_PATCHES', 'U') IS NULL
                CREATE TABLE dbo.DATA_PATCHES (
                    CODE NVARCHAR(60) NOT NULL PRIMARY KEY,
                    APPLIED_AT DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
                    APPLIED_BY NVARCHAR(100) NULL,
                    RESULT NVARCHAR(MAX) NULL
                )""");
    }

    public boolean isApplied(String code) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.DATA_PATCHES WHERE CODE = ?", Integer.class, code);
        return n != null && n > 0;
    }

    public List<PatchStatus> list() {
        ensureTable();
        List<PatchStatus> out = new ArrayList<>();
        for (Patch p : PATCHES) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT APPLIED_AT, APPLIED_BY, RESULT FROM dbo.DATA_PATCHES WHERE CODE = ?", p.code());
            Map<String, Object> r = rows.isEmpty() ? Map.of() : rows.get(0);
            out.add(new PatchStatus(p.code(), p.title(), p.description(),
                    r.get("APPLIED_AT") == null ? null : r.get("APPLIED_AT").toString(),
                    (String) r.get("APPLIED_BY"), (String) r.get("RESULT")));
        }
        return out;
    }

    /** Exécute le correctif, enregistre son résultat en base et dans le journal d'audit. */
    public String run(String code, String by) {
        ensureTable();
        String result = switch (code) {
            case INBOUND_VOIX_2026_10 -> inboundVoix(by);
            case TEAM_ASSIGNMENT_LOCKED_TRUE -> teamAssignmentLockedTrue();
            case RESEAUX_SOCIAUX_ROLES -> socialNetworkRoles();
            case AGENT_ROLES_SERVICES_SYNC -> administrationService.addMissingAgentPairs() + " rôle(s) ou service(s) d'agent ajouté(s) pour aligner rôles et services.";
            case INBOUND_MAIL_LEADERSHIP -> inboundMailLeadership();
            default -> throw com.ecobank.rccportal.util.ApiException.notFound("Correctif inconnu : " + code);
        };
        jdbc.update("""
                MERGE dbo.DATA_PATCHES AS t USING (SELECT ? AS CODE) AS s ON t.CODE = s.CODE
                WHEN MATCHED THEN UPDATE SET APPLIED_AT = SYSDATETIME(), APPLIED_BY = ?, RESULT = ?
                WHEN NOT MATCHED THEN INSERT (CODE, APPLIED_BY, RESULT) VALUES (?, ?, ?);""",
                code, by, result, code, by, result);
        String summary = result.length() > 1900 ? result.substring(0, 1900) + "…" : result;
        auditLogService.record(by, "DATA_PATCH", code + " — " + summary);
        log.warn("[DATA PATCH] {} appliqué :\n{}", code, result);
        return result;
    }

    // ───────────── Inbound Mail : Team Leader et comptes de test ─────────────

    String inboundMailLeadership() {
        StringBuilder out = new StringBuilder();
        List<User> all = userRepository.findAll().stream().filter(u -> u.getName() != null).toList();
        User loum = com.ecobank.rccportal.util.PersonNames.findUnique("LOUM OLIVIA", all, User::getName);
        if (loum == null) {
            out.append("⚠ LOUM Olivia : aucun compte unique à ce nom — à nommer dans Administration → Organigramme → Team Leaders par équipe.\n");
        } else {
            administrationService.setAccess(loum.getId(), "TEAM_LEADER", "INBOUND_MAIL");
            administrationService.grantRoleByName(loum.getId(), "Team Leader Inbound Mail", "Responsable d'équipe");
            out.append("• Team Leader Inbound Mail : ").append(loum.getName()).append(" (").append(loum.getUsername()).append(")\n");
        }
        for (String[] acc : new String[][]{{"teamleader.inboundmail", "TEAM_LEADER"}, {"agent.mail", "AGENT"}}) {
            userRepository.findFirstByUsernameIgnoreCase(acc[0]).ifPresentOrElse(u -> {
                administrationService.setAccess(u.getId(), acc[1], "INBOUND_MAIL");
                out.append("• Compte de test ").append(acc[0]).append(" : ").append(acc[1].equals("AGENT") ? "agent" : "Team Leader").append(" Inbound Mail\n");
            }, () -> out.append("• Compte de test ").append(acc[0]).append(" absent (mode bypass désactivé ?)\n"));
        }
        return out.toString().trim();
    }

    // ───────────── Rôle Réseaux sociaux → service Agent Réseaux sociaux ─────────────

    String socialNetworkRoles() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DISTINCT ur.USERS_ID AS U, u.NAME AS N FROM dbo.USER_ROLES ur JOIN dbo.ROLES r ON r.ID = ur.ROLES_ID JOIN dbo.USERS u ON u.ID = ur.USERS_ID
                WHERE (r.NAME LIKE N'%seau%soci%' OR r.NAME LIKE N'%Tchat%') AND r.NAME NOT LIKE N'%Team Leader%'""");
        Long svc = jdbc.queryForList("SELECT ID FROM dbo.SERVICES WHERE UPPER(CODE) = 'AGENT_TCHAT'", Long.class).stream().findFirst().orElse(null);
        if (svc == null) return "Service AGENT_TCHAT introuvable — rien fait.";
        List<String> done = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long uid = ((Number) r.get("U")).longValue();
            Integer has = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.USER_SERVICES WHERE USER_ID = ? AND SERVICE_ID = ?", Integer.class, uid, svc);
            if (has != null && has > 0) continue;
            administrationService.assignService(uid, svc);
            done.add(String.valueOf(r.get("N")));
        }
        return rows.size() + " personne(s) avec un rôle Réseaux sociaux ; service ajouté à " + done.size()
                + (done.isEmpty() ? "." : " : " + String.join(", ", done) + ".");
    }

    // ───────────── TEAM_ASSIGNMENT_LOCKED = True ─────────────

    String teamAssignmentLockedTrue() {
        int n = jdbc.update("UPDATE dbo.USERS SET TEAM_ASSIGNMENT_LOCKED = 1 WHERE TEAM_ASSIGNMENT_LOCKED = 0 OR TEAM_ASSIGNMENT_LOCKED IS NULL");
        // Valeur par défaut de la colonne : l'ancienne contrainte (DEFAULT 0) est remplacée par DEFAULT 1.
        jdbc.execute("""
                DECLARE @c SYSNAME = (SELECT dc.name FROM sys.default_constraints dc
                    JOIN sys.columns col ON col.object_id = dc.parent_object_id AND col.column_id = dc.parent_column_id
                    WHERE dc.parent_object_id = OBJECT_ID('dbo.USERS') AND col.name = 'TEAM_ASSIGNMENT_LOCKED');
                IF @c IS NOT NULL EXEC('ALTER TABLE dbo.USERS DROP CONSTRAINT [' + @c + ']');
                ALTER TABLE dbo.USERS ADD CONSTRAINT DF_USERS_TEAM_ASSIGNMENT_LOCKED DEFAULT 1 FOR TEAM_ASSIGNMENT_LOCKED;""");
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.USERS", Integer.class);
        return n + " compte(s) passé(s) à True (" + total + " compte(s) au total, tous à True). Valeur par défaut en base : True.";
    }

    // ───────────── Team Inbound Voix — octobre 2026 ─────────────

    String inboundVoix(String by) {
        StringBuilder out = new StringBuilder();
        renameTchatToSocialNetworks(out);

        // 1. Planning : même import que l'écran Planning (fichier Exceliam, mois d'octobre 2026, équipe Inbound Voix).
        byte[] csv = readResource(PLANNING_FILE);
        ScheduleImportResult r = scheduleService.importFromExcel(new BytesFile("inbound-voix-2026-10.csv", csv),
                "AGENT_INBOUND", "CI", "INBOUND VOICE", YearMonth.of(2026, 10), by);
        out.append("Planning octobre 2026 : ").append(r.rowsProcessed()).append(" agent(s), ").append(r.entriesCreated())
                .append(" jour(s) enregistré(s), ").append(r.usersAutoCreated()).append(" compte(s) créé(s) faute de compte existant.\n");

        // 2. Agents : rôle Agent Inbound Voice, service Agent Inbound, équipe Inbound Voix, aucun accès Team Leader.
        List<String> notFound = new ArrayList<>();
        Set<Long> agentIds = new HashSet<>();
        for (String name : planningNames(csv)) {
            Optional<User> u = scheduleService.findUserByPlanningName(name);
            if (u.isEmpty()) { notFound.add(name); continue; }
            agentIds.add(u.get().getId());
            List<String> changes = administrationService.alignAgent(u.get().getId(), "INBOUND_VOICE", AGENT_ROLE, "CI");
            out.append("• ").append(u.get().getName()).append(" (").append(u.get().getUsername()).append(") : ")
                    .append(changes.isEmpty() ? "déjà conforme" : String.join(", ", changes)).append('\n');
        }
        if (!notFound.isEmpty()) out.append("⚠ Compte introuvable ou ambigu (à rattacher à la main) : ").append(String.join(", ", notFound)).append('\n');

        // 3. Team Leaders désignés.
        List<User> all = userRepository.findAll().stream().filter(u -> u.getName() != null).toList();
        for (String[] tl : TEAM_LEADERS) {
            User u = com.ecobank.rccportal.util.PersonNames.findUnique(tl[0], all, User::getName);
            if (u == null) {
                out.append("⚠ Team Leader « ").append(tl[0]).append(" » : aucun compte unique à ce nom — à désigner dans l'organigramme.\n");
                continue;
            }
            if (agentIds.contains(u.getId())) {
                out.append("⚠ ").append(u.getName()).append(" figure dans le planning agents : laissé agent.\n");
                continue;
            }
            administrationService.setAccess(u.getId(), "TEAM_LEADER", tl[1]);
            administrationService.grantRoleByName(u.getId(), tl[2], "Responsable d'équipe");
            out.append("• Team Leader ").append(tl[1].equals("INBOUND_VOICE") ? "Inbound Voix" : "Inbound Mail").append(" : ")
                    .append(u.getName()).append(" (").append(u.getUsername()).append(")\n");
        }
        out.append("• Team Leader Réseaux sociaux : aucun nom fourni — à désigner dans l'organigramme (Accès → Team Leader · Réseaux sociaux).\n");
        return out.toString().trim();
    }

    /** Libellés en base : les services / rôles « Tchat » deviennent « Réseaux sociaux » (codes internes inchangés). */
    void renameTchatToSocialNetworks(StringBuilder out) {
        int n = jdbc.update("UPDATE dbo.SERVICES SET NAME = N'Agent Réseaux sociaux', DESCRIPTION = N'Conseiller clientèle — Réseaux sociaux' WHERE UPPER(CODE) = 'AGENT_TCHAT' AND NAME <> N'Agent Réseaux sociaux'")
                + jdbc.update("UPDATE dbo.SERVICES SET NAME = N'Team Leader Réseaux sociaux', DESCRIPTION = N'Responsable de l''équipe Réseaux sociaux' WHERE UPPER(CODE) = 'TEAM_LEADER_TCHAT' AND NAME <> N'Team Leader Réseaux sociaux'")
                + jdbc.update("UPDATE dbo.ROLES SET NAME = REPLACE(NAME, N'Tchat', N'Réseaux sociaux') WHERE NAME LIKE '%Tchat%'");
        out.append("Libellé « Tchat » → « Réseaux sociaux » : ").append(n).append(" service(s)/rôle(s) renommé(s).\n");
    }

    static List<String> planningNames(byte[] csv) {
        List<String> names = new ArrayList<>();
        for (String line : new String(csv, java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
            int c = line.indexOf(',');
            if (c <= 0 || !line.substring(0, c).trim().matches("\\d+")) continue;
            String rest = line.substring(c + 1);
            String name = rest.startsWith("\"") ? rest.substring(1, rest.indexOf('"', 1)) : rest.substring(0, rest.indexOf(','));
            names.add(name.trim());
        }
        return names;
    }

    private static byte[] readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Fichier " + path + " introuvable dans l'application.", e);
        }
    }

    /** Fichier en mémoire passé à l'import du planning (même chemin que l'écran d'import). */
    record BytesFile(String name, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return "text/csv"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(java.io.File dest) throws IOException { java.nio.file.Files.write(dest.toPath(), bytes); }
    }
}

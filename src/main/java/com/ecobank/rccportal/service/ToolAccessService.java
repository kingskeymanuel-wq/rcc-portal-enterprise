package com.ecobank.rccportal.service;

import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Outils & Portails → « Profils » : outils auxquels chaque agent a accès (Finesse, CRM, OMNIPLUS…), pour mesurer
 * son autonomie : pourcentage = outils accessibles ÷ outils du catalogue (catalogue dans external-tools.js).
 * Rangé par filiale et par équipe (même découpage que l'organigramme). Team Leader : son équipe, modifiable ;
 * Superviseur, RH, Head QA : tout, en lecture ; administrateur : tout, modifiable ; agent : son propre profil.
 * Stockage : dbo.AgentToolAccess (une ligne par agent et par outil).
 */
@Service
public class ToolAccessService {

    public record Profile(Long userId, String username, String name, String email, String country, String team, String teamLabel,
                          String level, List<String> tools, String updatedBy, LocalDateTime updatedAt, boolean canEdit) {}

    public record Profiles(boolean canEditAny, List<Profile> profiles) {}

    private static final Set<String> READ_ALL = Set.of("admin", "supervisor", "rh");

    private final AdminHierarchyService hierarchy;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private volatile boolean schemaReady;

    public ToolAccessService(AdminHierarchyService hierarchy, UserRepository users, JdbcTemplate jdbc) {
        this.hierarchy = hierarchy;
        this.users = users;
        this.jdbc = jdbc;
    }

    public Profiles profiles(AuthenticatedUser requester) {
        String role = role(requester);
        boolean admin = "admin".equals(role);
        String ledTeam = "team_leader".equals(role) ? users.findFirstByUsernameIgnoreCase(requester.username()).map(u -> u.getLedTeam()).orElse(null) : null;
        boolean all = READ_ALL.contains(role) || isHeadQa(requester);

        Map<Long, List<String>> tools = new HashMap<>();
        Map<Long, Object[]> meta = new HashMap<>();
        loadAccess(tools, meta);

        List<Profile> out = new ArrayList<>();
        for (AdminHierarchyService.TeamBlock block : hierarchy.hierarchy().teams()) {
            boolean teamVisible = all || (ledTeam != null && inLedScope(ledTeam, block.code()));
            boolean editable = admin || (ledTeam != null && inLedScope(ledTeam, block.code()));
            for (AdminHierarchyService.Person p : concat(block.leaders(), block.agents())) {
                if (!p.active()) continue;
                boolean self = requester.username() != null && requester.username().equalsIgnoreCase(p.username());
                if (!teamVisible && !self) continue;
                Object[] m = meta.get(p.id());
                boolean leader = block.leaders().contains(p);
                out.add(new Profile(p.id(), p.username(), p.name(), p.email(), p.country(), block.code(), block.label(),
                        leader ? "TEAM_LEADER" : "AGENT", tools.getOrDefault(p.id(), List.of()),
                        m == null ? null : (String) m[0], m == null ? null : (LocalDateTime) m[1],
                        editable && !(leader && !admin)));
            }
        }
        return new Profiles(admin || ledTeam != null, out);
    }

    /** Remplace la liste des outils d'un agent. Administrateur, ou Team Leader pour un agent de son équipe. */
    public Profile update(AuthenticatedUser requester, Long userId, List<String> codes) {
        Profile target = profiles(requester).profiles().stream().filter(p -> p.userId().equals(userId)).findFirst()
                .orElseThrow(() -> ApiException.forbidden("Agent hors de votre périmètre."));
        if (!target.canEdit()) throw ApiException.forbidden("Seul le Team Leader de l'agent (ou l'administrateur) modifie ses accès aux outils.");
        Set<String> clean = new LinkedHashSet<>();
        for (String c : codes == null ? List.<String>of() : codes) {
            if (c != null && c.matches("[a-z0-9][a-z0-9-]{1,79}")) clean.add(c);
        }
        ensureSchema();
        jdbc.update("DELETE FROM dbo.AgentToolAccess WHERE UserId = ?", userId);
        for (String c : clean) {
            jdbc.update("INSERT INTO dbo.AgentToolAccess (UserId, ToolCode, GrantedBy) VALUES (?, ?, ?)", userId, c, requester.username());
        }
        return new Profile(target.userId(), target.username(), target.name(), target.email(), target.country(), target.team(), target.teamLabel(),
                target.level(), List.copyOf(clean), requester.username(), LocalDateTime.now(), true);
    }

    /** Le Team Leader d'un pôle (Outbound, Inbound Mail) voit aussi ses sous-équipes (Télévente, Digitalisation ; Tchat, Rafiki). */
    static boolean inLedScope(String ledTeam, String teamCode) {
        if (ledTeam == null || teamCode == null) return false;
        String led = ledTeam.trim().toUpperCase(Locale.ROOT);
        if (led.equals(teamCode)) return true;
        TeamClassifier.Team parent = TeamClassifier.CHANNEL_TEAM.get(teamCode);
        return parent != null && parent.name().equals(led);
    }

    private void loadAccess(Map<Long, List<String>> tools, Map<Long, Object[]> meta) {
        try {
            ensureSchema();
            jdbc.query("SELECT UserId, ToolCode, GrantedBy, GrantedAt FROM dbo.AgentToolAccess ORDER BY GrantedAt", rs -> {
                long id = rs.getLong("UserId");
                tools.computeIfAbsent(id, k -> new ArrayList<>()).add(rs.getString("ToolCode"));
                java.sql.Timestamp at = rs.getTimestamp("GrantedAt");
                meta.put(id, new Object[]{rs.getString("GrantedBy"), at == null ? null : at.toLocalDateTime()});
            });
        } catch (RuntimeException e) {
            // table indisponible : profils sans outils
        }
    }

    private void ensureSchema() {
        if (schemaReady) return;
        jdbc.execute("""
                IF OBJECT_ID('dbo.AgentToolAccess', 'U') IS NULL
                CREATE TABLE dbo.AgentToolAccess (
                    UserId BIGINT NOT NULL,
                    ToolCode NVARCHAR(80) NOT NULL,
                    GrantedBy NVARCHAR(100) NULL,
                    GrantedAt DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT PK_AgentToolAccess PRIMARY KEY (UserId, ToolCode)
                )""");
        schemaReady = true;
    }

    private static String role(AuthenticatedUser u) {
        return u == null || u.role() == null ? "" : u.role().toLowerCase(Locale.ROOT);
    }

    private static boolean isHeadQa(AuthenticatedUser u) {
        return u != null && u.service() != null && u.service().toLowerCase(Locale.ROOT).replace('_', ' ').contains("superviseur qa");
    }

    private static List<AdminHierarchyService.Person> concat(List<AdminHierarchyService.Person> a, List<AdminHierarchyService.Person> b) {
        List<AdminHierarchyService.Person> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}

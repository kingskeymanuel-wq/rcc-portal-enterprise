package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.WorkflowRequest;
import com.ecobank.rccportal.repository.AgentScheduleRepository;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.WorkflowRequestRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Workflow de supervision des sous-responsables :
 * <ul>
 *   <li><b>Superviseur (Head RCC)</b> — périmètre « RCC » : ses Team Leaders (toutes équipes) et le Head QA ;
 *       il peut aussi ouvrir le périmètre « QA » ;</li>
 *   <li><b>Head QA</b> — périmètre « QA » : ses agents Quality Assurance et formateurs.</li>
 * </ul>
 * Pour chaque sous-responsable : tableau de bord de son activité réelle (dernière connexion, demandes de son
 * équipe en attente ou escaladées, planning de la semaine à venir, écoutes et feedbacks pour la QA, missions),
 * avec un niveau de vigilance. Le responsable lui confie des missions qui suivent un circuit complet :
 * À faire → En cours → À valider → Validée (ou À reprendre, ou Annulée), chaque étape tracée et notifiée.
 */
@Service
public class SupervisionService {

    public static final String SCOPE_RCC = "RCC";
    public static final String SCOPE_QA = "QA";

    public static final List<String> STATUSES = List.of("A_FAIRE", "EN_COURS", "A_VALIDER", "A_REPRENDRE", "VALIDEE", "ANNULEE");
    static final Set<String> OPEN = Set.of("A_FAIRE", "EN_COURS", "A_VALIDER", "A_REPRENDRE");
    static final List<String> PRIORITIES = List.of("BASSE", "NORMALE", "HAUTE", "URGENTE");

    public record Indicator(String key, String label, String value, String level) {}

    public record Member(Long userId, String username, String name, String role, String team, String teamLabel,
                         LocalDateTime lastLogin, List<Indicator> indicators, List<String> issues, String level,
                         int openMissions, int overdueMissions, int toValidate) {}

    public record Board(String scope, String scopeLabel, List<String> scopes, List<Member> members, Map<String, Integer> totals,
                        LocalDateTime generatedAt) {}

    public record Task(Long id, String scope, String managerUsername, String managerName, Long assigneeUserId, String assigneeName,
                       String assigneeUsername, String title, String details, String category, String priority, LocalDate dueDate,
                       String status, String assigneeComment, String managerComment, LocalDateTime createdAt, LocalDateTime updatedAt,
                       LocalDateTime submittedAt, LocalDateTime validatedAt, boolean overdue) {}

    public record TaskEvent(Long id, String actor, String actorName, String action, String comment, LocalDateTime at) {}

    public record CreateTask(String scope, List<Long> assigneeUserIds, String title, String details, String category, String priority,
                             LocalDate dueDate) {}

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final WorkflowRequestRepository requests;
    private final AgentScheduleRepository schedules;
    private final RccNotificationRepository notifications;
    private final QaTeamActivityService qaTeam;

    public SupervisionService(JdbcTemplate jdbc, UserRepository users, WorkflowRequestRepository requests,
                              AgentScheduleRepository schedules, RccNotificationRepository notifications, QaTeamActivityService qaTeam) {
        this.jdbc = jdbc;
        this.users = users;
        this.requests = requests;
        this.schedules = schedules;
        this.notifications = notifications;
        this.qaTeam = qaTeam;
    }

    // ── Droits ────────────────────────────────────────────────────────────

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    /** Périmètres que l'utilisateur supervise : Superviseur → RCC ; Head QA → QA ; administrateur → les deux. */
    public static List<String> scopesOf(AuthenticatedUser u) {
        if (u == null) return List.of();
        String role = up(u.role());
        String service = up(u.service());
        if (role.equals("ADMIN")) return List.of(SCOPE_RCC, SCOPE_QA);
        List<String> out = new ArrayList<>();
        boolean supervisor = role.equals("SUPERVISOR") || service.equals("SUPERVISEUR");
        if (supervisor) out.add(SCOPE_RCC);
        // Le Superviseur (Head RCC) voit aussi l'équipe QA, sous la responsabilité du Head QA.
        if (supervisor || service.equals("SUPERVISEUR_QA") || role.equals("QA_SUPERVISOR")) out.add(SCOPE_QA);
        return out;
    }

    static String resolveScope(AuthenticatedUser u, String wanted) {
        List<String> scopes = scopesOf(u);
        if (scopes.isEmpty()) throw ApiException.forbidden("Réservé au Superviseur (Head RCC), au Head QA et à l'administrateur.");
        if (wanted == null || wanted.isBlank()) return scopes.get(0);
        String w = up(wanted);
        if (!scopes.contains(w)) throw ApiException.forbidden("Ce périmètre de supervision ne vous est pas attribué.");
        return w;
    }

    static String scopeLabel(String scope) {
        return SCOPE_QA.equals(scope) ? "Équipe Quality Assurance" : "Team Leaders & Head QA";
    }

    // ── Sous-responsables ─────────────────────────────────────────────────

    record Sub(Long id, String username, String name, String role, String team) {}

    private static final Map<String, String> TEAM_LABELS = Map.of("INBOUND_VOICE", "Inbound Voix", "INBOUND_MAIL", "Inbound Mail",
            "TCHAT", "Réseaux sociaux", "RAFIKI", "Rafiki", "CIB", "CIB", "OUTBOUND", "Outbound", "QA", "Quality Assurance",
            "TELEVENTE", "Télévente", "DIGITALISATION", "Digitalisation");

    /** Team Leaders (équipe menée, service ou rôle Team Leader) et Head QA — comptes actifs. */
    List<Sub> teamLeadersAndHeadQa() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.ID, u.USERNAME, u.NAME, u.LED_TEAM,
                       MAX(CASE WHEN UPPER(s.CODE) LIKE 'TEAM_LEADER_%' THEN UPPER(s.CODE) END) AS TL_SERVICE,
                       MAX(CASE WHEN UPPER(s.CODE) = 'SUPERVISEUR_QA' OR UPPER(r.NAME) = 'QA_SUPERVISOR' THEN 1 ELSE 0 END) AS HEAD_QA,
                       MAX(CASE WHEN UPPER(r.NAME) LIKE '%TEAM%LEADER%' THEN 1 ELSE 0 END) AS TL_ROLE
                FROM dbo.USERS u
                LEFT JOIN dbo.USER_SERVICES us ON us.USER_ID = u.ID LEFT JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID
                LEFT JOIN dbo.USER_ROLES ur ON ur.USERS_ID = u.ID LEFT JOIN dbo.ROLES r ON r.ID = ur.ROLES_ID
                WHERE (u.ACCOUNT_ENABLED IS NULL OR u.ACCOUNT_ENABLED = 1)
                """ + com.ecobank.rccportal.util.Filiale.sql("u") + """
                GROUP BY u.ID, u.USERNAME, u.NAME, u.LED_TEAM
                """);
        List<Sub> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String led = (String) r.get("LED_TEAM");
            String tlService = (String) r.get("TL_SERVICE");
            boolean headQa = ((Number) r.get("HEAD_QA")).intValue() == 1;
            boolean tlRole = ((Number) r.get("TL_ROLE")).intValue() == 1;
            String team = led != null && !led.isBlank() ? up(led) : tlService != null ? tlService.substring("TEAM_LEADER_".length()) : null;
            Long id = ((Number) r.get("ID")).longValue();
            if (team != null || tlRole) out.add(new Sub(id, (String) r.get("USERNAME"), (String) r.get("NAME"), "Team Leader", team));
            else if (headQa) out.add(new Sub(id, (String) r.get("USERNAME"), (String) r.get("NAME"), "Head QA", "QA"));
        }
        out.sort(Comparator.comparing((Sub s) -> s.role().equals("Head QA") ? 1 : 0)
                .thenComparing(s -> s.team() == null ? "ZZZ" : s.team()).thenComparing(s -> s.name() == null ? "" : s.name(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    List<Sub> subordinates(String scope) {
        if (SCOPE_QA.equals(scope)) {
            List<Sub> out = new ArrayList<>();
            for (QaTeamActivityService.Person p : qaTeam.members()) {
                if ("Head QA".equals(p.role())) continue;
                out.add(new Sub(p.id(), p.username(), p.name(), p.role(), "QA"));
            }
            return out;
        }
        return teamLeadersAndHeadQa();
    }

    private Map<Long, LocalDateTime> lastLogins() {
        Map<Long, LocalDateTime> out = new HashMap<>();
        try {
            jdbc.query("SELECT UserId, MAX(OccurredAt) AS LastAt FROM dbo.LoginAudit WHERE EventType = 'login_success' GROUP BY UserId", rs -> {
                java.sql.Timestamp t = rs.getTimestamp("LastAt");
                if (t != null) out.put(rs.getLong("UserId"), t.toLocalDateTime());
            });
        } catch (RuntimeException ignored) {
            // journal indisponible
        }
        return out;
    }

    private Map<Long, List<String>> serviceCodes() {
        Map<Long, List<String>> out = new HashMap<>();
        jdbc.query("SELECT us.USER_ID, s.CODE FROM dbo.USER_SERVICES us JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID", rs -> {
            if (rs.getString("CODE") != null) out.computeIfAbsent(rs.getLong("USER_ID"), k -> new ArrayList<>()).add(rs.getString("CODE"));
        });
        return out;
    }

    // ── Tableau de bord ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Board board(AuthenticatedUser requester, String scopeParam) {
        String scope = resolveScope(requester, scopeParam);
        List<Sub> subs = subordinates(scope);
        Map<Long, LocalDateTime> logins = lastLogins();
        Map<Long, int[]> missions = missionCounts(scope);
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        // Agents par équipe menée (Team Leaders) et couverture du planning des 7 prochains jours.
        Map<String, Set<Long>> teamAgents = new HashMap<>();
        Set<Long> planned = new HashSet<>();
        Map<Long, List<String>> codes = Map.of();
        Map<String, QaTeamActivityService.QaMember> qa = new HashMap<>();
        boolean needsQa = subs.stream().anyMatch(s -> "QA".equals(s.team()));
        boolean needsTeams = subs.stream().anyMatch(s -> s.team() != null && !"QA".equals(s.team()));
        if (needsTeams) {
            codes = serviceCodes();
            Set<String> leaderIds = new HashSet<>();
            subs.forEach(s -> leaderIds.add(String.valueOf(s.id())));
            for (User u : users.findAll()) {
                if (Boolean.FALSE.equals(u.getAccountEnabled()) || leaderIds.contains(String.valueOf(u.getId()))) continue;
                if (!com.ecobank.rccportal.util.Filiale.matches(u.getAffiliateBranch())) continue;
                List<String> c = codes.getOrDefault(u.getId(), List.of());
                for (Sub s : subs) {
                    if (s.team() == null || "QA".equals(s.team())) continue;
                    if (TeamClassifier.belongsTo(s.team(), u.getActivity(), c)) teamAgents.computeIfAbsent(s.team(), k -> new HashSet<>()).add(u.getId());
                }
            }
            for (AgentSchedule a : schedules.findByWorkDateBetween(today, today.plusDays(6))) {
                if (a.getUser() != null) planned.add(a.getUser().getId());
            }
        }
        if (needsQa) {
            try {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                for (QaTeamActivityService.QaMember m : qaTeam.teamActivity(requester, monday, today).members()) qa.put(m.username().toLowerCase(Locale.ROOT), m);
            } catch (RuntimeException ignored) {
                // activité QA indisponible
            }
        }

        List<Member> out = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        totals.put("members", subs.size());
        totals.put("RED", 0); totals.put("AMBER", 0); totals.put("GREEN", 0);
        totals.put("openMissions", 0); totals.put("overdueMissions", 0); totals.put("toValidate", 0);
        for (Sub s : subs) {
            List<Indicator> ind = new ArrayList<>();
            List<String> issues = new ArrayList<>();
            boolean red = false;
            LocalDateTime last = logins.get(s.id());
            long idleDays = last == null ? -1 : ChronoUnit.DAYS.between(last.toLocalDate(), today);
            if (last == null) { issues.add("Jamais connecté au portail"); red = true; }
            else if (idleDays >= 3) { issues.add("Pas connecté depuis " + idleDays + " jours"); red = true; }
            else if (idleDays >= 2) issues.add("Pas connecté depuis " + idleDays + " jours");

            if ("QA".equals(s.team())) {
                QaTeamActivityService.QaMember m = s.username() == null ? null : qa.get(s.username().toLowerCase(Locale.ROOT));
                long evals = m == null ? 0 : m.voiceEvaluations() + m.writtenEvaluations();
                long pendingFb = m == null ? 0 : m.feedbacksPending();
                ind.add(new Indicator("evaluations", "Écoutes cette semaine", String.valueOf(evals), evals == 0 ? "AMBER" : "GREEN"));
                ind.add(new Indicator("feedbacks", "Feedbacks en attente", String.valueOf(pendingFb), pendingFb > 5 ? "AMBER" : "GREEN"));
                ind.add(new Indicator("coachings", "Coachings", String.valueOf(m == null ? 0 : m.coachings()), "INFO"));
                if (evals == 0 && today.getDayOfWeek().getValue() >= 3) issues.add("Aucune écoute depuis lundi");
                if (pendingFb > 5) issues.add(pendingFb + " feedbacks non restitués");
            } else if (s.team() != null) {
                int size = teamAgents.getOrDefault(s.team(), Set.of()).size();
                long withPlanning = teamAgents.getOrDefault(s.team(), Set.of()).stream().filter(planned::contains).count();
                int coverage = size == 0 ? 0 : (int) Math.round(withPlanning * 100.0 / size);
                int pending = 0, escalated = 0, stale = 0;
                User tl = users.findById(s.id()).orElse(null);
                if (tl != null) {
                    for (WorkflowRequest r : requests.findByAssignedToOrderByCreatedAtDesc(tl)) {
                        if (!"PENDING".equalsIgnoreCase(r.getStatus())) continue;
                        pending++;
                        if (r.getEscalatedAt() != null) escalated++;
                        LocalDateTime created = r.getCreatedAt();
                        if (created != null && ChronoUnit.DAYS.between(created.toLocalDate(), today) >= 2) stale++;
                    }
                }
                ind.add(new Indicator("team", "Agents dans l'équipe", String.valueOf(size), size == 0 ? "AMBER" : "INFO"));
                ind.add(new Indicator("planning", "Planning des 7 prochains jours", size == 0 ? "—" : coverage + " %",
                        size == 0 ? "INFO" : coverage < 50 ? "RED" : coverage < 90 ? "AMBER" : "GREEN"));
                ind.add(new Indicator("pending", "Demandes à traiter", String.valueOf(pending), stale > 0 ? "AMBER" : "GREEN"));
                ind.add(new Indicator("escalated", "Demandes escaladées", String.valueOf(escalated), escalated > 0 ? "RED" : "GREEN"));
                if (size == 0) issues.add("Aucun agent rattaché à l'équipe " + TEAM_LABELS.getOrDefault(s.team(), s.team()));
                else if (coverage < 50) { issues.add("Planning de la semaine à venir incomplet (" + coverage + " %)"); red = true; }
                else if (coverage < 90) issues.add("Planning à compléter (" + coverage + " % des agents)");
                if (escalated > 0) { issues.add(escalated + " demande(s) escaladée(s) au Superviseur"); red = true; }
                if (stale > 0) issues.add(stale + " demande(s) en attente depuis plus de 2 jours");
            } else {
                issues.add("Team Leader sans équipe menée");
            }

            int[] m = missions.getOrDefault(s.id(), new int[3]);
            if (m[1] > 0) { issues.add(m[1] + " mission(s) en retard"); red = true; }
            if (m[2] > 0) issues.add(m[2] + " mission(s) à valider");
            String level = red ? "RED" : issues.isEmpty() ? "GREEN" : "AMBER";
            totals.merge(level, 1, Integer::sum);
            totals.merge("openMissions", m[0], Integer::sum);
            totals.merge("overdueMissions", m[1], Integer::sum);
            totals.merge("toValidate", m[2], Integer::sum);
            out.add(new Member(s.id(), s.username(), s.name() == null ? s.username() : s.name(), s.role(), s.team(),
                    s.team() == null ? "Sans équipe" : TEAM_LABELS.getOrDefault(s.team(), s.team()), last, ind, issues, level, m[0], m[1], m[2]));
        }
        out.sort(Comparator.comparing((Member x) -> List.of("RED", "AMBER", "GREEN").indexOf(x.level())).thenComparing(Member::name, String.CASE_INSENSITIVE_ORDER));
        return new Board(scope, scopeLabel(scope), scopesOf(requester), out, totals, now);
    }

    /** Par sous-responsable : missions ouvertes, en retard, rendues à valider. */
    private Map<Long, int[]> missionCounts(String scope) {
        Map<Long, int[]> out = new HashMap<>();
        try {
            jdbc.query("SELECT AssigneeUserId, Status, DueDate FROM dbo.SupervisionTasks WHERE Scope = ?", rs -> {
                String st = rs.getString("Status");
                if (!OPEN.contains(st)) return;
                int[] c = out.computeIfAbsent(rs.getLong("AssigneeUserId"), k -> new int[3]);
                c[0]++;
                java.sql.Date due = rs.getDate("DueDate");
                if (due != null && due.toLocalDate().isBefore(LocalDate.now()) && !"A_VALIDER".equals(st)) c[1]++;
                if ("A_VALIDER".equals(st)) c[2]++;
            }, scope);
        } catch (RuntimeException ignored) {
            // table pas encore créée
        }
        return out;
    }

    // ── Missions ──────────────────────────────────────────────────────────

    private Task mapTask(java.sql.ResultSet rs, Map<Long, User> byId, Map<String, User> byUsername) throws java.sql.SQLException {
        User a = byId.get(rs.getLong("AssigneeUserId"));
        User m = byUsername.get(rs.getString("ManagerUsername").toLowerCase(Locale.ROOT));
        java.sql.Date due = rs.getDate("DueDate");
        String status = rs.getString("Status");
        return new Task(rs.getLong("TaskId"), rs.getString("Scope"), rs.getString("ManagerUsername"), m == null ? rs.getString("ManagerUsername") : nameOf(m),
                rs.getLong("AssigneeUserId"), a == null ? "#" + rs.getLong("AssigneeUserId") : nameOf(a), a == null ? null : a.getUsername(),
                rs.getString("Title"), rs.getString("Details"), rs.getString("Category"), rs.getString("Priority"),
                due == null ? null : due.toLocalDate(), status, rs.getString("AssigneeComment"), rs.getString("ManagerComment"),
                ts(rs, "CreatedAt"), ts(rs, "UpdatedAt"), ts(rs, "SubmittedAt"), ts(rs, "ValidatedAt"),
                due != null && due.toLocalDate().isBefore(LocalDate.now()) && OPEN.contains(status) && !"A_VALIDER".equals(status));
    }

    private static LocalDateTime ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    private static String nameOf(User u) {
        return u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername();
    }

    private List<Task> queryTasks(String where, Object... args) {
        Map<Long, User> byId = new HashMap<>();
        Map<String, User> byUsername = new HashMap<>();
        for (User u : users.findAll()) {
            byId.put(u.getId(), u);
            if (u.getUsername() != null) byUsername.put(u.getUsername().toLowerCase(Locale.ROOT), u);
        }
        List<Task> out = new ArrayList<>();
        jdbc.query("SELECT * FROM dbo.SupervisionTasks WHERE " + where + " ORDER BY CASE Status WHEN 'A_VALIDER' THEN 0 WHEN 'A_REPRENDRE' THEN 1 "
                + "WHEN 'EN_COURS' THEN 2 WHEN 'A_FAIRE' THEN 3 ELSE 4 END, DueDate, TaskId DESC", rs -> {
            out.add(mapTask(rs, byId, byUsername));
        }, args);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Task> tasksForManager(AuthenticatedUser requester, String scopeParam) {
        String scope = resolveScope(requester, scopeParam);
        return queryTasks("Scope = ? AND (Status NOT IN ('VALIDEE', 'ANNULEE') OR UpdatedAt >= DATEADD(day, -30, SYSUTCDATETIME()))", scope);
    }

    @Transactional(readOnly = true)
    public List<Task> myTasks(AuthenticatedUser requester) {
        User me = users.findFirstByUsernameIgnoreCase(requester.username()).orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        try {
            return queryTasks("AssigneeUserId = ? AND (Status NOT IN ('VALIDEE', 'ANNULEE') OR UpdatedAt >= DATEADD(day, -30, SYSUTCDATETIME()))", me.getId());
        } catch (RuntimeException e) {
            return List.of(); // table pas encore créée
        }
    }

    @Transactional
    public List<Task> create(AuthenticatedUser requester, CreateTask body) {
        String scope = resolveScope(requester, body == null ? null : body.scope());
        if (body.title() == null || body.title().isBlank()) throw ApiException.badRequest("Indiquez l'intitulé de la mission.");
        if (body.assigneeUserIds() == null || body.assigneeUserIds().isEmpty()) throw ApiException.badRequest("Choisissez au moins un responsable.");
        String priority = body.priority() == null || body.priority().isBlank() ? "NORMALE" : up(body.priority());
        if (!PRIORITIES.contains(priority)) throw ApiException.badRequest("Priorité inconnue.");
        if (body.dueDate() != null && body.dueDate().isBefore(LocalDate.now())) throw ApiException.badRequest("L'échéance ne peut pas être dans le passé.");
        Set<Long> allowed = new HashSet<>();
        subordinates(scope).forEach(s -> allowed.add(s.id()));
        String title = cut(body.title(), 200);
        String details = cut(body.details(), 2000);
        List<Long> ids = new ArrayList<>();
        for (Long uid : new LinkedHashSet<>(body.assigneeUserIds())) {
            if (!allowed.contains(uid)) throw ApiException.forbidden("Cette personne ne fait pas partie de vos sous-responsables.");
            Long id = jdbc.queryForObject("INSERT INTO dbo.SupervisionTasks (Scope, ManagerUsername, AssigneeUserId, Title, Details, Category, Priority, DueDate) "
                            + "OUTPUT INSERTED.TaskId VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Long.class,
                    scope, requester.username(), uid, title, details, cut(body.category(), 30), priority,
                    body.dueDate() == null ? null : java.sql.Date.valueOf(body.dueDate()));
            ids.add(id);
            event(id, requester.username(), "CREATION", null);
            users.findById(uid).ifPresent(u -> notify(u, "Nouvelle mission de " + displayName(requester) + " : « " + title + " »"
                    + (body.dueDate() == null ? "" : " — échéance " + body.dueDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                    + ". Retrouvez-la dans « Mes missions » de votre portail."));
        }
        if (ids.isEmpty()) return List.of();
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        return queryTasks("TaskId IN (" + in + ")", ids.toArray());
    }

    /**
     * Étapes du circuit. Sous-responsable : START (prise en charge), SUBMIT (rendu, commentaire requis).
     * Responsable : VALIDATE, REWORK (renvoi, commentaire requis), CANCEL. Les deux : COMMENT.
     */
    @Transactional
    public Task act(AuthenticatedUser requester, Long taskId, String actionParam, String commentParam) {
        List<Task> found = queryTasks("TaskId = ?", taskId);
        if (found.isEmpty()) throw ApiException.notFound("Mission introuvable.");
        Task t = found.get(0);
        String action = up(actionParam);
        String comment = cut(commentParam, 1000);
        boolean isAssignee = t.assigneeUsername() != null && t.assigneeUsername().equalsIgnoreCase(requester.username());
        boolean isManager = scopesOf(requester).contains(t.scope());
        String next;
        switch (action) {
            case "START" -> {
                requireActor(isAssignee, "Seul le responsable désigné peut prendre en charge cette mission.");
                requireStatus(t, "A_FAIRE", "A_REPRENDRE");
                next = "EN_COURS";
            }
            case "SUBMIT" -> {
                requireActor(isAssignee, "Seul le responsable désigné peut rendre cette mission.");
                requireStatus(t, "A_FAIRE", "EN_COURS", "A_REPRENDRE");
                if (comment == null) throw ApiException.badRequest("Décrivez ce qui a été fait avant de rendre la mission.");
                next = "A_VALIDER";
            }
            case "VALIDATE" -> {
                requireActor(isManager, "Seul le responsable qui supervise peut valider.");
                requireStatus(t, "A_VALIDER");
                next = "VALIDEE";
            }
            case "REWORK" -> {
                requireActor(isManager, "Seul le responsable qui supervise peut renvoyer une mission.");
                requireStatus(t, "A_VALIDER");
                if (comment == null) throw ApiException.badRequest("Indiquez ce qui doit être repris.");
                next = "A_REPRENDRE";
            }
            case "CANCEL" -> {
                requireActor(isManager, "Seul le responsable qui supervise peut annuler.");
                requireStatus(t, "A_FAIRE", "EN_COURS", "A_VALIDER", "A_REPRENDRE");
                next = "ANNULEE";
            }
            case "COMMENT" -> {
                requireActor(isManager || isAssignee, "Cette mission ne vous concerne pas.");
                if (comment == null) throw ApiException.badRequest("Commentaire vide.");
                next = t.status();
            }
            default -> throw ApiException.badRequest("Action inconnue.");
        }
        String sql = "UPDATE dbo.SupervisionTasks SET Status = ?, UpdatedAt = SYSUTCDATETIME()"
                + (action.equals("SUBMIT") ? ", SubmittedAt = SYSUTCDATETIME(), AssigneeComment = ?" : "")
                + (action.equals("VALIDATE") ? ", ValidatedAt = SYSUTCDATETIME()" + (comment != null ? ", ManagerComment = ?" : "") : "")
                + (action.equals("REWORK") ? ", ManagerComment = ?" : "")
                + " WHERE TaskId = ?";
        List<Object> args = new ArrayList<>();
        args.add(next);
        if (action.equals("SUBMIT") || action.equals("REWORK") || (action.equals("VALIDATE") && comment != null)) args.add(comment);
        args.add(taskId);
        jdbc.update(sql, args.toArray());
        event(taskId, requester.username(), action, comment);

        String who = displayName(requester);
        if (isAssignee && !isManager || action.equals("START") || action.equals("SUBMIT")) {
            users.findFirstByUsernameIgnoreCase(t.managerUsername()).ifPresent(m -> notify(m, switch (action) {
                case "START" -> who + " a pris en charge la mission « " + t.title() + " ».";
                case "SUBMIT" -> who + " a rendu la mission « " + t.title() + " » : à valider.";
                default -> who + " a commenté la mission « " + t.title() + " ».";
            }));
        } else {
            users.findById(t.assigneeUserId()).ifPresent(a -> notify(a, switch (action) {
                case "VALIDATE" -> "Mission « " + t.title() + " » validée par " + who + ".";
                case "REWORK" -> "Mission « " + t.title() + " » à reprendre : " + comment;
                case "CANCEL" -> "Mission « " + t.title() + " » annulée par " + who + ".";
                default -> who + " a commenté la mission « " + t.title() + " » : " + comment;
            }));
        }
        return queryTasks("TaskId = ?", taskId).get(0);
    }

    @Transactional(readOnly = true)
    public List<TaskEvent> events(AuthenticatedUser requester, Long taskId) {
        List<Task> found = queryTasks("TaskId = ?", taskId);
        if (found.isEmpty()) throw ApiException.notFound("Mission introuvable.");
        Task t = found.get(0);
        boolean allowed = scopesOf(requester).contains(t.scope())
                || (t.assigneeUsername() != null && t.assigneeUsername().equalsIgnoreCase(requester.username()));
        if (!allowed) throw ApiException.forbidden("Cette mission ne vous concerne pas.");
        Map<String, String> names = new HashMap<>();
        for (User u : users.findAll()) if (u.getUsername() != null) names.put(u.getUsername().toLowerCase(Locale.ROOT), nameOf(u));
        List<TaskEvent> out = new ArrayList<>();
        jdbc.query("SELECT * FROM dbo.SupervisionTaskEvents WHERE TaskId = ? ORDER BY At, EventId", rs -> {
            String actor = rs.getString("Actor");
            out.add(new TaskEvent(rs.getLong("EventId"), actor, names.getOrDefault(actor.toLowerCase(Locale.ROOT), actor),
                    rs.getString("Action"), rs.getString("Comment"), ts(rs, "At")));
        }, taskId);
        return out;
    }

    private static void requireActor(boolean ok, String message) {
        if (!ok) throw ApiException.forbidden(message);
    }

    private static void requireStatus(Task t, String... allowed) {
        if (!Arrays.asList(allowed).contains(t.status())) throw ApiException.badRequest("Action impossible : la mission est « " + label(t.status()) + " ».");
    }

    static String label(String status) {
        return switch (status == null ? "" : status) {
            case "A_FAIRE" -> "à faire";
            case "EN_COURS" -> "en cours";
            case "A_VALIDER" -> "à valider";
            case "A_REPRENDRE" -> "à reprendre";
            case "VALIDEE" -> "validée";
            case "ANNULEE" -> "annulée";
            default -> String.valueOf(status);
        };
    }

    private void event(Long taskId, String actor, String action, String comment) {
        jdbc.update("INSERT INTO dbo.SupervisionTaskEvents (TaskId, Actor, Action, Comment) VALUES (?, ?, ?, ?)", taskId, actor, action, comment);
    }

    private void notify(User target, String content) {
        try {
            notifications.save(RccNotification.builder().targetUser(target).content(cut(content, 500)).isRead(false).build());
        } catch (RuntimeException ignored) {
            // notification best-effort
        }
    }

    private String displayName(AuthenticatedUser u) {
        return u.name() != null && !u.name().isBlank() ? u.name() : u.username();
    }

    static String cut(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}

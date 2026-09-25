package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.AssignedTask;
import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AssignedTaskRepository;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Meeting de performance « tête-à-tête » Team Leader ↔ agent.
 *
 * <ol>
 *   <li>Le Team Leader planifie (agent de SON équipe, date, lieu, objectif, responsables en copie)
 *       → invitation à l'agent et aux personnes en copie + tâche « Compte rendu » dans SES
 *       Tâches & notes (Workflow).</li>
 *   <li>Après le meeting, le Team Leader remplit le compte rendu (raisons, points forts, axes
 *       d'amélioration, plan d'action, date de suivi) → tâche « Lire et approuver » chez l'agent.</li>
 *   <li>L'agent coche « Lu et approuvé » (commentaire facultatif) → le meeting remonte chez le
 *       HEAD QA et le HEAD RCC (+ copies + Team Leader).</li>
 * </ol>
 */
@Service
public class CoachingMeetingService {

    private static final Logger log = LoggerFactory.getLogger(CoachingMeetingService.class);
    private static final DateTimeFormatter FR = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm");

    public static final String PLANNED = "PLANIFIE";
    public static final String REPORTED = "COMPTE_RENDU";
    public static final String APPROVED = "APPROUVE";
    public static final String CANCELLED = "ANNULE";

    public record Person(String username, String name, String label) {}

    public record ScheduleRequest(String agentUsername, LocalDateTime scheduledAt, Integer durationMinutes, String location,
                                  String objective, String agenda, List<String> ccUsernames) {}

    public record ReportRequest(String reasons, String strengths, String improvements, String actionPlan, LocalDate followUpDate) {}

    public record AckRequest(Boolean approved, String comment) {}

    public record Meeting(Long id, Person teamLeader, Person agent, LocalDateTime scheduledAt, Integer durationMinutes, String location,
                          String objective, String agenda, List<Person> cc, String status, String reasons, String strengths,
                          String improvements, String actionPlan, LocalDate followUpDate, LocalDateTime reportedAt,
                          String agentComment, LocalDateTime acknowledgedAt, LocalDateTime createdAt,
                          boolean canReport, boolean canAcknowledge, boolean canCancel) {}

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final AssignedTaskRepository taskRepository;
    private final RccNotificationRepository notificationRepository;
    private final NotificationService mail;
    private final TeamLeaderService teamLeaderService;

    public CoachingMeetingService(JdbcTemplate jdbc, UserRepository userRepository, AssignedTaskRepository taskRepository,
                                  RccNotificationRepository notificationRepository, NotificationService mail,
                                  TeamLeaderService teamLeaderService) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.notificationRepository = notificationRepository;
        this.mail = mail;
        this.teamLeaderService = teamLeaderService;
    }

    // ── Rôles ──────────────────────────────────────────────────────────────

    static boolean isAdmin(AuthenticatedUser u) { return u != null && "ADMIN".equalsIgnoreCase(u.role()); }
    static boolean isTeamLeader(AuthenticatedUser u) { return u != null && "TEAM_LEADER".equalsIgnoreCase(u.role()); }
    static boolean isHeadRcc(AuthenticatedUser u) { return u != null && "SUPERVISOR".equalsIgnoreCase(u.role()); }
    static boolean isHeadQa(AuthenticatedUser u) {
        return u != null && u.service() != null && u.service().trim().toUpperCase(Locale.ROOT).replace(' ', '_').equals("SUPERVISEUR_QA");
    }
    static boolean isHead(AuthenticatedUser u) { return isAdmin(u) || isHeadRcc(u) || isHeadQa(u); }

    /** Utilisateurs HEAD QA (service Superviseur QA) et HEAD RCC (rôle/service Superviseur). */
    List<User> heads() {
        List<Long> ids = jdbc.queryForList("""
                SELECT DISTINCT u.ID FROM dbo.USERS u
                LEFT JOIN dbo.USER_SERVICES us ON us.USER_ID = u.ID LEFT JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID
                LEFT JOIN dbo.USER_ROLES ur ON ur.USERS_ID = u.ID LEFT JOIN dbo.ROLES r ON r.ID = ur.ROLES_ID
                WHERE UPPER(s.CODE) IN ('SUPERVISEUR_QA', 'SUPERVISEUR')
                   OR UPPER(r.NAME) IN ('SUPERVISOR', 'HEAD RCC (SUPERVISEUR)')
                """, Long.class);
        return userRepository.findAllById(ids);
    }

    /** Responsables pouvant être mis en copie (Head QA/RCC, QA, RH, Team Leaders, administration). */
    @Transactional(readOnly = true)
    public List<Person> ccCandidates(AuthenticatedUser requester) {
        requireTeamLeader(requester);
        return jdbc.query("""
                SELECT DISTINCT u.USERNAME, u.NAME,
                  CASE WHEN UPPER(s.CODE) = 'SUPERVISEUR_QA' THEN 'Head QA'
                       WHEN UPPER(s.CODE) = 'SUPERVISEUR' OR UPPER(r.NAME) IN ('SUPERVISOR', 'HEAD RCC (SUPERVISEUR)') THEN 'Head RCC'
                       WHEN UPPER(s.CODE) = 'QUALITY_ASSURANCE' THEN 'Quality Assurance'
                       WHEN UPPER(s.CODE) = 'RH' OR UPPER(r.NAME) = 'RH' THEN 'RH'
                       WHEN UPPER(s.CODE) LIKE 'TEAM_LEADER%' OR UPPER(r.NAME) = 'TEAM_LEADER' THEN 'Team Leader'
                       ELSE 'Administration' END AS Label
                FROM dbo.USERS u
                LEFT JOIN dbo.USER_SERVICES us ON us.USER_ID = u.ID LEFT JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID
                LEFT JOIN dbo.USER_ROLES ur ON ur.USERS_ID = u.ID LEFT JOIN dbo.ROLES r ON r.ID = ur.ROLES_ID
                WHERE (u.ACCOUNT_ENABLED IS NULL OR u.ACCOUNT_ENABLED = 1)
                  AND (UPPER(s.CODE) IN ('SUPERVISEUR_QA', 'SUPERVISEUR', 'QUALITY_ASSURANCE', 'RH') OR UPPER(s.CODE) LIKE 'TEAM_LEADER%'
                       OR UPPER(r.NAME) IN ('SUPERVISOR', 'HEAD RCC (SUPERVISEUR)', 'RH', 'TEAM_LEADER', 'ADMIN'))
                """, (rs, i) -> new Person(rs.getString("USERNAME"), rs.getString("NAME"), rs.getString("Label")))
                .stream()
                .filter(p -> !p.username().equalsIgnoreCase(requester.username()))
                // un utilisateur peut sortir plusieurs fois (plusieurs rôles) : on garde le libellé le plus « haut »
                .collect(java.util.stream.Collectors.toMap(p -> p.username().toLowerCase(Locale.ROOT), p -> p,
                        (a, b) -> rank(a.label()) <= rank(b.label()) ? a : b, LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparingInt((Person p) -> rank(p.label())).thenComparing(p -> String.valueOf(p.name()), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static int rank(String label) {
        return switch (label == null ? "" : label) {
            case "Head QA" -> 0;
            case "Head RCC" -> 1;
            case "Quality Assurance" -> 2;
            case "RH" -> 3;
            case "Team Leader" -> 4;
            default -> 5;
        };
    }

    private void requireTeamLeader(AuthenticatedUser requester) {
        if (!isTeamLeader(requester) && !isAdmin(requester)) {
            throw ApiException.forbidden("Réservé aux Team Leaders.");
        }
    }

    // ── Planification ─────────────────────────────────────────────────────

    @Transactional
    public Meeting schedule(AuthenticatedUser requester, ScheduleRequest r) {
        requireTeamLeader(requester);
        User tl = user(requester.username());
        if (r.agentUsername() == null || r.agentUsername().isBlank()) throw ApiException.badRequest("Choisissez l'agent.");
        User agent = userRepository.findFirstByUsernameIgnoreCase(r.agentUsername().trim())
                .orElseThrow(() -> ApiException.notFound("Agent inconnu."));
        if (!isAdmin(requester)) {
            boolean inTeam = teamLeaderService.teamMembers(requester).stream()
                    .anyMatch(m -> m.username() != null && m.username().equalsIgnoreCase(agent.getUsername()));
            if (!inTeam) throw ApiException.forbidden("Cet agent ne fait pas partie de votre équipe.");
        }
        if (r.scheduledAt() == null) throw ApiException.badRequest("Date et heure du meeting obligatoires.");
        String objective = required(r.objective(), 300, "Objectif");
        String agenda = optional(r.agenda(), 2000, "Points à aborder");
        String location = optional(r.location(), 200, "Lieu");
        Integer duration = r.durationMinutes() == null ? 30 : Math.max(5, Math.min(240, r.durationMinutes()));

        List<User> cc = new ArrayList<>();
        if (r.ccUsernames() != null) {
            Set<String> allowed = new HashSet<>();
            ccCandidates(requester).forEach(p -> allowed.add(p.username().toLowerCase(Locale.ROOT)));
            for (String u : new LinkedHashSet<>(r.ccUsernames())) {
                if (u == null || u.isBlank()) continue;
                if (!allowed.contains(u.trim().toLowerCase(Locale.ROOT))) throw ApiException.badRequest("« " + u + " » ne peut pas être mis en copie.");
                userRepository.findFirstByUsernameIgnoreCase(u.trim()).ifPresent(cc::add);
            }
        }
        String ccJoined = cc.isEmpty() ? null : String.join(",", cc.stream().map(User::getUsername).toList());

        jdbc.update("INSERT INTO dbo.CoachingMeetings (TeamLeaderId, AgentId, ScheduledAt, DurationMinutes, Location, Objective, Agenda, CcUsernames, Status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", tl.getId(), agent.getId(), Timestamp.valueOf(r.scheduledAt()), duration, location,
                objective, agenda, ccJoined, PLANNED);
        Long id = jdbc.queryForObject("SELECT MAX(MeetingId) FROM dbo.CoachingMeetings WHERE TeamLeaderId = ? AND AgentId = ?", Long.class, tl.getId(), agent.getId());

        // Tâche du Team Leader : compte rendu à remplir après le meeting (Workflow → Tâches & notes).
        taskRepository.save(AssignedTask.builder()
                .title("Compte rendu — meeting avec " + label(agent))
                .description("Objectif : " + objective + "\nPrévu le " + r.scheduledAt().format(FR) + (location != null ? " — " + location : "")
                        + "\nÀ l'issue du meeting, remplissez le compte rendu : il sera envoyé à l'agent pour « lu et approuvé ».")
                .assignedTo(tl).createdByUser(tl).dueDate(r.scheduledAt().toLocalDate()).priority("HIGH").status("OPEN")
                .category("MEETING_REPORT").relatedMeetingId(id).build());

        String when = r.scheduledAt().format(FR);
        notify(List.of(agent), "📅 Meeting de performance avec " + label(tl) + " le " + when + (location != null ? " (" + location + ")" : "")
                + " — objectif : " + objective, "RCC Portal — Meeting avec votre Team Leader", id);
        notify(cc, "📅 Vous êtes en copie du meeting de performance de " + label(agent) + " avec " + label(tl) + " le " + when
                + " — objectif : " + objective, "RCC Portal — Meeting de performance (copie)", id);
        return get(requester, id);
    }

    // ── Compte rendu (Team Leader) ────────────────────────────────────────

    @Transactional
    public Meeting report(AuthenticatedUser requester, Long id, ReportRequest r) {
        Row m = row(id);
        User me = user(requester.username());
        if (!m.teamLeaderId.equals(me.getId()) && !isAdmin(requester)) throw ApiException.forbidden("Seul le Team Leader du meeting remplit le compte rendu.");
        if (CANCELLED.equals(m.status) || APPROVED.equals(m.status)) throw ApiException.badRequest("Ce meeting est clôturé.");
        String reasons = required(r.reasons(), 2000, "Raisons du meeting / constat");
        String improvements = required(r.improvements(), 2000, "Axes d'amélioration");
        String actionPlan = required(r.actionPlan(), 2000, "Plan d'action");
        String strengths = optional(r.strengths(), 2000, "Points forts");
        jdbc.update("UPDATE dbo.CoachingMeetings SET Reasons = ?, Strengths = ?, Improvements = ?, ActionPlan = ?, FollowUpDate = ?, "
                + "ReportedAt = SYSUTCDATETIME(), Status = ? WHERE MeetingId = ?", reasons, strengths, improvements, actionPlan,
                r.followUpDate() == null ? null : java.sql.Date.valueOf(r.followUpDate()), REPORTED, id);
        closeTasks(id, "MEETING_REPORT");

        User agent = userRepository.findById(m.agentId).orElseThrow();
        User tl = userRepository.findById(m.teamLeaderId).orElseThrow();
        if (taskRepository.findByRelatedMeetingId(id).stream().noneMatch(t -> "MEETING_ACK".equals(t.getCategory()))) {
            taskRepository.save(AssignedTask.builder()
                    .title("Lire et approuver — compte rendu du meeting avec " + label(tl))
                    .description("Votre Team Leader a rédigé le compte rendu de votre meeting de performance. Lisez-le puis cochez « Lu et approuvé ».")
                    .assignedTo(agent).createdByUser(tl).dueDate(LocalDate.now().plusDays(2)).priority("HIGH").status("OPEN")
                    .category("MEETING_ACK").relatedMeetingId(id).build());
        }
        notify(List.of(agent), "📝 Compte rendu de votre meeting avec " + label(tl) + " disponible : merci de le lire et de l'approuver (Workflow → Tâches & notes).",
                "RCC Portal — Compte rendu de meeting à approuver", id);
        return get(requester, id);
    }

    // ── Lu et approuvé (agent) ────────────────────────────────────────────

    @Transactional
    public Meeting acknowledge(AuthenticatedUser requester, Long id, AckRequest r) {
        Row m = row(id);
        User me = user(requester.username());
        if (!m.agentId.equals(me.getId())) throw ApiException.forbidden("Seul l'agent concerné approuve le compte rendu.");
        if (!REPORTED.equals(m.status)) throw ApiException.badRequest("Le compte rendu n'est pas encore disponible ou a déjà été approuvé.");
        if (!Boolean.TRUE.equals(r.approved())) throw ApiException.badRequest("Cochez « J'ai lu et j'approuve » pour valider.");
        jdbc.update("UPDATE dbo.CoachingMeetings SET AgentComment = ?, AcknowledgedAt = SYSUTCDATETIME(), Status = ? WHERE MeetingId = ?",
                optional(r.comment(), 1000, "Commentaire"), APPROVED, id);
        closeTasks(id, "MEETING_ACK");

        User tl = userRepository.findById(m.teamLeaderId).orElseThrow();
        List<User> recipients = new ArrayList<>(heads());
        recipients.addAll(ccUsers(m.cc));
        recipients.add(tl);
        notify(dedupe(recipients, me), "✅ Meeting de performance « lu et approuvé » par " + label(me) + " (Team Leader : " + label(tl)
                + ") — objectif : " + m.objective + ". Compte rendu consultable dans Workflow → Meetings.",
                "RCC Portal — Compte rendu de meeting approuvé", id);
        return get(requester, id);
    }

    @Transactional
    public Meeting cancel(AuthenticatedUser requester, Long id) {
        Row m = row(id);
        User me = user(requester.username());
        if (!m.teamLeaderId.equals(me.getId()) && !isAdmin(requester)) throw ApiException.forbidden("Seul le Team Leader du meeting peut l'annuler.");
        if (!PLANNED.equals(m.status)) throw ApiException.badRequest("Seul un meeting planifié (sans compte rendu) peut être annulé.");
        jdbc.update("UPDATE dbo.CoachingMeetings SET Status = ? WHERE MeetingId = ?", CANCELLED, id);
        closeTasks(id, "MEETING_REPORT");
        User agent = userRepository.findById(m.agentId).orElseThrow();
        List<User> recipients = new ArrayList<>(List.of(agent));
        recipients.addAll(ccUsers(m.cc));
        notify(dedupe(recipients, me), "❌ Meeting du " + m.scheduledAt.format(FR) + " avec " + label(agent) + " annulé par " + label(me) + ".",
                "RCC Portal — Meeting annulé", id);
        return get(requester, id);
    }

    // ── Lecture ───────────────────────────────────────────────────────────

    /** Meetings visibles : Head QA / Head RCC / admin = tous ; sinon ceux où l'on est TL, agent ou en copie. */
    @Transactional(readOnly = true)
    public List<Meeting> list(AuthenticatedUser requester) {
        User me = user(requester.username());
        boolean all = isHead(requester);
        List<Row> rows = jdbc.query("SELECT * FROM dbo.CoachingMeetings ORDER BY ScheduledAt DESC", (rs, i) -> Row.of(rs));
        List<Meeting> out = new ArrayList<>();
        for (Row r : rows) {
            if (all || r.teamLeaderId.equals(me.getId()) || r.agentId.equals(me.getId()) || r.cc.contains(me.getUsername().toLowerCase(Locale.ROOT))) {
                out.add(toMeeting(r, requester, me));
            }
            if (out.size() >= 300) break;
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Meeting get(AuthenticatedUser requester, Long id) {
        Row r = row(id);
        User me = user(requester.username());
        if (!isHead(requester) && !r.teamLeaderId.equals(me.getId()) && !r.agentId.equals(me.getId())
                && !r.cc.contains(me.getUsername().toLowerCase(Locale.ROOT))) {
            throw ApiException.notFound("Meeting introuvable.");
        }
        return toMeeting(r, requester, me);
    }

    // ── Interne ───────────────────────────────────────────────────────────

    record Row(Long id, Long teamLeaderId, Long agentId, LocalDateTime scheduledAt, Integer duration, String location,
                       String objective, String agenda, List<String> cc, String status, String reasons, String strengths,
                       String improvements, String actionPlan, LocalDate followUp, LocalDateTime reportedAt, String agentComment,
                       LocalDateTime acknowledgedAt, LocalDateTime createdAt) {
        static Row of(java.sql.ResultSet rs) throws java.sql.SQLException {
            String cc = rs.getString("CcUsernames");
            return new Row(rs.getLong("MeetingId"), rs.getLong("TeamLeaderId"), rs.getLong("AgentId"), ts(rs, "ScheduledAt"),
                    (Integer) rs.getObject("DurationMinutes"), rs.getString("Location"), rs.getString("Objective"), rs.getString("Agenda"),
                    cc == null || cc.isBlank() ? List.of() : Arrays.stream(cc.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList(),
                    rs.getString("Status"), rs.getString("Reasons"), rs.getString("Strengths"), rs.getString("Improvements"),
                    rs.getString("ActionPlan"), rs.getDate("FollowUpDate") == null ? null : rs.getDate("FollowUpDate").toLocalDate(),
                    ts(rs, "ReportedAt"), rs.getString("AgentComment"), ts(rs, "AcknowledgedAt"), ts(rs, "CreatedAt"));
        }
        private static LocalDateTime ts(java.sql.ResultSet rs, String c) throws java.sql.SQLException {
            Timestamp t = rs.getTimestamp(c);
            return t == null ? null : t.toLocalDateTime();
        }
    }

    private Row row(Long id) {
        List<Row> rows = jdbc.query("SELECT * FROM dbo.CoachingMeetings WHERE MeetingId = ?", (rs, i) -> Row.of(rs), id);
        if (rows.isEmpty()) throw ApiException.notFound("Meeting introuvable.");
        return rows.get(0);
    }

    private Meeting toMeeting(Row r, AuthenticatedUser requester, User me) {
        User tl = userRepository.findById(r.teamLeaderId).orElse(null);
        User agent = userRepository.findById(r.agentId).orElse(null);
        List<Person> cc = ccUsers(r.cc).stream().map(u -> new Person(u.getUsername(), u.getName(), null)).toList();
        boolean isTl = r.teamLeaderId.equals(me.getId()) || isAdmin(requester);
        return new Meeting(r.id, person(tl), person(agent), r.scheduledAt, r.duration, r.location, r.objective, r.agenda, cc, r.status,
                r.reasons, r.strengths, r.improvements, r.actionPlan, r.followUp, r.reportedAt, r.agentComment, r.acknowledgedAt, r.createdAt,
                isTl && (PLANNED.equals(r.status) || REPORTED.equals(r.status)),
                r.agentId.equals(me.getId()) && REPORTED.equals(r.status),
                isTl && PLANNED.equals(r.status));
    }

    private List<User> ccUsers(List<String> usernames) {
        List<User> out = new ArrayList<>();
        for (String u : usernames) userRepository.findFirstByUsernameIgnoreCase(u).ifPresent(out::add);
        return out;
    }

    private void closeTasks(Long meetingId, String category) {
        taskRepository.findByRelatedMeetingId(meetingId).stream()
                .filter(t -> category.equals(t.getCategory()) && "OPEN".equals(t.getStatus()))
                .forEach(t -> { t.setStatus("DONE"); taskRepository.save(t); });
    }

    private void notify(Collection<User> recipients, String content, String subject, Long meetingId) {
        for (User u : recipients) {
            notificationRepository.save(RccNotification.builder().targetUser(u).content(content).isRead(false)
                    .actionType("OPEN_MEETING").actionTarget(String.valueOf(meetingId)).build());
        }
        try {
            List<String> emails = recipients.stream().map(User::getEmail).filter(Objects::nonNull).filter(e -> !e.isBlank()).distinct().toList();
            if (!emails.isEmpty()) mail.sendBroadcastEmail(emails, subject, content);
        } catch (RuntimeException e) {
            log.warn("Meeting : e-mail non envoyé ({}), notification en app conservée.", e.getMessage());
        }
    }

    private static List<User> dedupe(List<User> users, User exclude) {
        LinkedHashMap<Long, User> map = new LinkedHashMap<>();
        for (User u : users) if (u != null && !u.getId().equals(exclude.getId())) map.putIfAbsent(u.getId(), u);
        return new ArrayList<>(map.values());
    }

    private User user(String username) {
        return userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
    }

    private static Person person(User u) {
        return u == null ? null : new Person(u.getUsername(), u.getName(), null);
    }

    private static String label(User u) {
        return u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername();
    }

    private static String required(String v, int max, String field) {
        String t = optional(v, max, field);
        if (t == null) throw ApiException.badRequest(field + " : obligatoire.");
        return t;
    }

    private static String optional(String v, int max, String field) {
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        if (t.length() > max) throw ApiException.badRequest(field + " : " + max + " caractères maximum.");
        return t;
    }
}

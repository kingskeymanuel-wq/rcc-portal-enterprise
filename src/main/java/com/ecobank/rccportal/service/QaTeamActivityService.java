package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.QualityEvaluationResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Activité de l'équipe Quality Assurance, agent QA par agent QA — Portail Head QA et Portail
 * Superviseur : écoutes (évaluations d'appels), évaluations écrites (mails / chat), feedbacks
 * rendus, coachings, formations et cours créés, questions d'évaluation (quiz), contenus publiés
 * (base de connaissances, procédures, masques de mail, actualités) et temps actif sur le portail.
 */
@Service
public class QaTeamActivityService {

    public record QaMember(Long userId, String username, String name, String role, long voiceEvaluations, long writtenEvaluations,
                           long agentsEvaluated, Double avgScore, Double passRate, long feedbacksDone, long feedbacksPending,
                           long coachings, long formations, long courses, long quizQuestions, long contents, long portalSeconds,
                           LocalDateTime lastActivity) {}

    public record DayCount(LocalDate day, long voice, long written) {}

    public record TeamActivity(LocalDate from, LocalDate to, List<QaMember> members, List<DayCount> daily,
                               Map<String, Long> totals) {}

    public record ActivityItem(String type, String label, String detail, LocalDateTime at) {}

    private final JdbcTemplate jdbc;
    private final QualityEvaluationService evaluations;

    public QaTeamActivityService(JdbcTemplate jdbc, QualityEvaluationService evaluations) {
        this.jdbc = jdbc;
        this.evaluations = evaluations;
    }

    /** Head QA (service Superviseur QA), Superviseur (Head RCC), rôle QA_SUPERVISOR ou administrateur. */
    static boolean canView(AuthenticatedUser u) {
        if (u == null) return false;
        String role = u.role() == null ? "" : u.role().toUpperCase(Locale.ROOT);
        String service = u.service() == null ? "" : u.service().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return role.equals("ADMIN") || role.equals("SUPERVISOR") || role.equals("QA_SUPERVISOR")
                || service.equals("SUPERVISEUR_QA") || service.equals("SUPERVISEUR");
    }

    private void require(AuthenticatedUser u) {
        if (!canView(u)) throw ApiException.forbidden("Réservé au Head QA, au Superviseur et à l'administrateur.");
    }

    record Person(Long id, String username, String name, String role) {}

    /** Membres de l'équipe QA : service Quality Assurance / Superviseur QA / Formateur, ou rôle QA. */
    List<Person> members() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.ID, u.USERNAME, u.NAME, MAX(CASE WHEN UPPER(s.CODE) = 'SUPERVISEUR_QA' OR UPPER(r.NAME) = 'QA_SUPERVISOR' THEN 3
                                                          WHEN UPPER(s.CODE) = 'FORMATEUR' THEN 2 ELSE 1 END) AS RANK_
                FROM dbo.USERS u
                LEFT JOIN dbo.USER_SERVICES us ON us.USER_ID = u.ID LEFT JOIN dbo.SERVICES s ON s.ID = us.SERVICE_ID
                LEFT JOIN dbo.USER_ROLES ur ON ur.USERS_ID = u.ID LEFT JOIN dbo.ROLES r ON r.ID = ur.ROLES_ID
                WHERE (u.ACCOUNT_ENABLED IS NULL OR u.ACCOUNT_ENABLED = 1)
                  AND (UPPER(s.CODE) IN ('QUALITY_ASSURANCE', 'SUPERVISEUR_QA', 'FORMATEUR') OR UPPER(r.NAME) IN ('QA', 'QA_SUPERVISOR'))
                GROUP BY u.ID, u.USERNAME, u.NAME
                """);
        List<Person> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            int rank = ((Number) r.get("RANK_")).intValue();
            out.add(new Person(((Number) r.get("ID")).longValue(), (String) r.get("USERNAME"), (String) r.get("NAME"),
                    rank == 3 ? "Head QA" : rank == 2 ? "Formateur" : "Agent QA"));
        }
        return out;
    }

    public TeamActivity teamActivity(AuthenticatedUser requester, LocalDate from, LocalDate to) {
        require(requester);
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) throw ApiException.badRequest("Période invalide.");
        List<Person> people = members();
        Map<String, Person> byUsername = new HashMap<>();
        people.forEach(p -> byUsername.put(p.username().toLowerCase(Locale.ROOT), p));

        // Écoutes / évaluations écrites (score et réussite calculés par le service qualité).
        Map<Long, long[]> evalCounts = new HashMap<>();        // voice, written, feedbackDone, feedbackPending, passed
        Map<Long, double[]> scoreSum = new HashMap<>();
        Map<Long, Set<String>> agents = new HashMap<>();
        Map<Long, LocalDateTime> last = new HashMap<>();
        Map<LocalDate, long[]> daily = new TreeMap<>();
        for (QualityEvaluationResponse e : evaluations.listAll()) {
            if (e.evaluatorMatricule() == null || e.evaluationDate() == null) continue;
            if (e.evaluationDate().isBefore(start) || e.evaluationDate().isAfter(end)) continue;
            Person p = byUsername.get(e.evaluatorMatricule().toLowerCase(Locale.ROOT));
            if (p == null) continue;
            boolean written = "CHAT".equalsIgnoreCase(e.channel()) || "MAIL".equalsIgnoreCase(e.channel());
            long[] c = evalCounts.computeIfAbsent(p.id(), k -> new long[5]);
            c[written ? 1 : 0]++;
            if ("done".equalsIgnoreCase(e.feedbackStatus())) c[2]++; else c[3]++;
            if (e.passed()) c[4]++;
            double[] s = scoreSum.computeIfAbsent(p.id(), k -> new double[2]);
            s[0] += e.scorePercentage(); s[1]++;
            agents.computeIfAbsent(p.id(), k -> new HashSet<>()).add(String.valueOf(e.agentMatricule()));
            last.merge(p.id(), e.evaluationDate().atStartOfDay(), (a, b) -> a.isAfter(b) ? a : b);
            daily.computeIfAbsent(e.evaluationDate(), k -> new long[2])[written ? 1 : 0]++;
        }

        Timestamp fromTs = Timestamp.valueOf(start.atStartOfDay()), toTs = Timestamp.valueOf(end.plusDays(1).atStartOfDay());
        Map<Long, Long> coachings = countBy("AssignedTasks", "CreatedByUserId", "Category = 'QA_COACHING'", fromTs, toTs, last);
        Map<Long, Long> formations = countBy("TrainingFormations", "CreatedByUserId", null, fromTs, toTs, last);
        Map<Long, Long> courses = countBy("Courses", "CreatedByUserId", null, fromTs, toTs, last);
        Map<Long, Long> quiz = countBy("QuizQuestions", "CreatedByUserId", null, fromTs, toTs, last);
        Map<Long, Long> contents = new HashMap<>();
        for (String table : List.of("KnowledgeArticles", "Procedures", "MailTemplates", "NewsArticles")) {
            countBy(table, "CreatedByUserId", null, fromTs, toTs, last).forEach((k, v) -> contents.merge(k, v, Long::sum));
        }
        Map<Long, Long> portal = new HashMap<>();
        try {
            jdbc.query("SELECT UserId, SUM(CAST(Seconds AS BIGINT)) AS S FROM dbo.PageUsageDaily WHERE UsageDate BETWEEN ? AND ? GROUP BY UserId",
                    rs -> { portal.put(rs.getLong("UserId"), rs.getLong("S")); }, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
        } catch (RuntimeException ignored) {
            // mesure d'utilisation absente : temps portail non affiché
        }

        List<QaMember> out = new ArrayList<>();
        for (Person p : people) {
            long[] c = evalCounts.getOrDefault(p.id(), new long[5]);
            double[] s = scoreSum.get(p.id());
            long total = c[0] + c[1];
            out.add(new QaMember(p.id(), p.username(), p.name(), p.role(), c[0], c[1], agents.getOrDefault(p.id(), Set.of()).size(),
                    s == null ? null : round1(s[0] / s[1]), total == 0 ? null : round1(c[4] * 100.0 / total), c[2], c[3],
                    coachings.getOrDefault(p.id(), 0L), formations.getOrDefault(p.id(), 0L), courses.getOrDefault(p.id(), 0L),
                    quiz.getOrDefault(p.id(), 0L), contents.getOrDefault(p.id(), 0L), portal.getOrDefault(p.id(), 0L), last.get(p.id())));
        }
        out.sort(Comparator.comparingLong((QaMember m) -> m.voiceEvaluations() + m.writtenEvaluations()).reversed()
                .thenComparing(m -> String.valueOf(m.name()), String.CASE_INSENSITIVE_ORDER));

        List<DayCount> series = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end) && series.size() < 370; d = d.plusDays(1)) {
            long[] v = daily.getOrDefault(d, new long[2]);
            series.add(new DayCount(d, v[0], v[1]));
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("members", (long) out.size());
        totals.put("voiceEvaluations", out.stream().mapToLong(QaMember::voiceEvaluations).sum());
        totals.put("writtenEvaluations", out.stream().mapToLong(QaMember::writtenEvaluations).sum());
        totals.put("feedbacksPending", out.stream().mapToLong(QaMember::feedbacksPending).sum());
        totals.put("coachings", out.stream().mapToLong(QaMember::coachings).sum());
        totals.put("formations", out.stream().mapToLong(m -> m.formations() + m.courses()).sum());
        totals.put("quizQuestions", out.stream().mapToLong(QaMember::quizQuestions).sum());
        totals.put("contents", out.stream().mapToLong(QaMember::contents).sum());
        return new TeamActivity(start, end, out, series, totals);
    }

    /** Nombre de lignes créées par utilisateur sur la période (table absente = aucune activité). */
    private Map<Long, Long> countBy(String table, String userCol, String where, Timestamp from, Timestamp to, Map<Long, LocalDateTime> last) {
        Map<Long, Long> out = new HashMap<>();
        try {
            jdbc.query("SELECT " + userCol + " AS U, COUNT(*) AS N, MAX(CreatedAt) AS L FROM dbo." + table + " WHERE " + userCol
                            + " IS NOT NULL AND CreatedAt >= ? AND CreatedAt < ?" + (where != null ? " AND " + where : "") + " GROUP BY " + userCol,
                    rs -> {
                        long u = rs.getLong("U");
                        out.put(u, rs.getLong("N"));
                        Timestamp l = rs.getTimestamp("L");
                        if (l != null) last.merge(u, l.toLocalDateTime(), (a, b) -> a.isAfter(b) ? a : b);
                    }, from, to);
        } catch (RuntimeException ignored) {
            // table non encore créée sur cette base
        }
        return out;
    }

    /** Journal d'activité d'un agent QA (80 dernières actions de la période). */
    public List<ActivityItem> memberActivity(AuthenticatedUser requester, String username, LocalDate from, LocalDate to) {
        require(requester);
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(29);
        Person p = members().stream().filter(x -> x.username().equalsIgnoreCase(username)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Membre QA introuvable."));
        List<ActivityItem> items = new ArrayList<>();
        for (QualityEvaluationResponse e : evaluations.listAll()) {
            if (e.evaluatorMatricule() == null || !e.evaluatorMatricule().equalsIgnoreCase(p.username())) continue;
            if (e.evaluationDate() == null || e.evaluationDate().isBefore(start) || e.evaluationDate().isAfter(end)) continue;
            boolean written = "CHAT".equalsIgnoreCase(e.channel()) || "MAIL".equalsIgnoreCase(e.channel());
            items.add(new ActivityItem(written ? "WRITTEN" : "VOICE", (written ? "Évaluation écrite — " : "Écoute — ") + (e.agentName() != null ? e.agentName() : e.agentMatricule()),
                    Math.round(e.scorePercentage()) + " % · " + (e.knockedOut() ? "éliminatoire" : e.passed() ? "réussi" : "insuffisant")
                            + (e.motifLabel() != null ? " · " + e.motifLabel() : "") + ("done".equalsIgnoreCase(e.feedbackStatus()) ? " · feedback rendu" : " · feedback à faire"),
                    e.createdAt() != null ? e.createdAt() : e.evaluationDate().atStartOfDay()));
        }
        Timestamp fromTs = Timestamp.valueOf(start.atStartOfDay()), toTs = Timestamp.valueOf(end.plusDays(1).atStartOfDay());
        titled(items, "COACHING", "Coaching — ", "AssignedTasks", "Title", "Category = 'QA_COACHING'", p.id(), fromTs, toTs);
        titled(items, "FORMATION", "Formation créée — ", "TrainingFormations", "Title", null, p.id(), fromTs, toTs);
        titled(items, "FORMATION", "Cours créé — ", "Courses", "Title", null, p.id(), fromTs, toTs);
        titled(items, "QUIZ", "Question d'évaluation — ", "QuizQuestions", "QuestionText", null, p.id(), fromTs, toTs);
        titled(items, "CONTENT", "Article (base de connaissances) — ", "KnowledgeArticles", "Title", null, p.id(), fromTs, toTs);
        titled(items, "CONTENT", "Procédure — ", "Procedures", "Title", null, p.id(), fromTs, toTs);
        titled(items, "CONTENT", "Masque de mail — ", "MailTemplates", "Subject", null, p.id(), fromTs, toTs);
        titled(items, "CONTENT", "Actualité — ", "NewsArticles", "Title", null, p.id(), fromTs, toTs);
        items.sort(Comparator.comparing(ActivityItem::at).reversed());
        return items.size() > 80 ? items.subList(0, 80) : items;
    }

    private void titled(List<ActivityItem> items, String type, String prefix, String table, String titleCol, String where, Long userId,
                        Timestamp from, Timestamp to) {
        try {
            jdbc.query("SELECT TOP 40 " + titleCol + " AS T, CreatedAt FROM dbo." + table + " WHERE CreatedByUserId = ? AND CreatedAt >= ? AND CreatedAt < ?"
                            + (where != null ? " AND " + where : "") + " ORDER BY CreatedAt DESC",
                    rs -> {
                        String t = rs.getString("T");
                        if (t != null && t.length() > 120) t = t.substring(0, 119) + "…";
                        items.add(new ActivityItem(type, prefix + (t == null ? "" : t), null, rs.getTimestamp("CreatedAt").toLocalDateTime()));
                    }, userId, from, to);
        } catch (RuntimeException ignored) {
            // table absente
        }
    }

    private static Double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}

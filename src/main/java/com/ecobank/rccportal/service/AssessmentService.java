package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Évaluations programmées (onglet Évaluation) : la QA dépose le support d'un cours (ou part d'un cours existant), les
 * questions sont rédigées automatiquement (QuizGenerationService), elle les relit, choisit l'équipe et la période puis
 * clique sur « Terminer ». Les agents de l'équipe et leur Team Leader sont prévenus ; chaque agent passe l'évaluation
 * une fois pendant la période ; le score est visible de l'agent (Ma Performance), de son Team Leader et de la QA.
 *
 * Tables créées au premier usage : dbo.Assessments, dbo.AssessmentQuestions, dbo.AssessmentAttempts.
 */
@Slf4j
@Service
public class AssessmentService {

    public static final String ALL = "ALL";
    public static final List<String> TEAMS = List.of("ALL", "INBOUND_VOICE", "INBOUND_MAIL", "TCHAT", "RAFIKI", "CIB", "OUTBOUND", "TELEVENTE", "DIGITALISATION");
    public static final Map<String, String> TEAM_LABELS = Map.of("ALL", "Toutes les équipes", "INBOUND_VOICE", "Inbound Voix", "INBOUND_MAIL", "Inbound Mail",
            "TCHAT", "Réseaux sociaux", "RAFIKI", "Rafiki", "CIB", "CIB", "OUTBOUND", "Outbound", "TELEVENTE", "Télévente", "DIGITALISATION", "Digitalisation");

    public record Question(Integer id, int position, String text, List<String> options, Integer correct, String explanation) {}

    public record Assessment(Integer id, String title, String description, String teamCode, String teamLabel, LocalDate startsOn,
                             LocalDate endsOn, int passScore, String status, Integer courseId, String sourceName, String generatedBy,
                             String createdBy, LocalDateTime createdAt, int questionCount, int attempts, int passed, Double averageScore,
                             List<Question> questions) {}

    /** Vue agent : son évaluation, sans les bonnes réponses tant qu'il ne l'a pas passée. */
    public record MyAssessment(Assessment assessment, boolean open, boolean done, Integer score, Boolean passed, LocalDateTime submittedAt) {}

    public record Result(Long userId, String username, String name, String team, int score, int correct, int total, boolean passed,
                         LocalDateTime submittedAt) {}

    public record Results(Assessment assessment, List<Result> results, List<String> notYet, int expected) {}

    public record SubmitResult(int score, int correct, int total, boolean passed, int passScore, List<Map<String, Object>> review) {}

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final UserServiceAssignmentRepository services;
    private final RccNotificationRepository notifications;
    private final QuizGenerationService generator;
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean ready;

    public AssessmentService(JdbcTemplate jdbc, UserRepository users, UserServiceAssignmentRepository services,
                             RccNotificationRepository notifications, QuizGenerationService generator) {
        this.jdbc = jdbc;
        this.users = users;
        this.services = services;
        this.notifications = notifications;
        this.generator = generator;
    }

    // ── Schéma ───────────────────────────────────────────────────────────────────────

    private void ensureSchema() {
        if (ready) return;
        jdbc.execute("IF OBJECT_ID('dbo.Assessments','U') IS NULL CREATE TABLE dbo.Assessments ("
                + "Id INT IDENTITY PRIMARY KEY, Title NVARCHAR(200) NOT NULL, Description NVARCHAR(1000) NULL, TeamCode NVARCHAR(30) NOT NULL,"
                + "StartsOn DATE NOT NULL, EndsOn DATE NOT NULL, PassScore INT NOT NULL DEFAULT 70, Status NVARCHAR(20) NOT NULL,"
                + "CourseId INT NULL, SourceName NVARCHAR(260) NULL, GeneratedBy NVARCHAR(20) NULL, CreatedBy NVARCHAR(100) NULL,"
                + "CreatedAt DATETIME2 NOT NULL DEFAULT SYSDATETIME(), PublishedAt DATETIME2 NULL)");
        jdbc.execute("IF OBJECT_ID('dbo.AssessmentQuestions','U') IS NULL CREATE TABLE dbo.AssessmentQuestions ("
                + "Id INT IDENTITY PRIMARY KEY, AssessmentId INT NOT NULL, Position INT NOT NULL, QuestionText NVARCHAR(1000) NOT NULL,"
                + "OptionsJson NVARCHAR(MAX) NOT NULL, CorrectIndex INT NOT NULL, Explanation NVARCHAR(1000) NULL)");
        jdbc.execute("IF OBJECT_ID('dbo.AssessmentAttempts','U') IS NULL CREATE TABLE dbo.AssessmentAttempts ("
                + "Id INT IDENTITY PRIMARY KEY, AssessmentId INT NOT NULL, UserId BIGINT NOT NULL, Score INT NOT NULL, Correct INT NOT NULL,"
                + "Total INT NOT NULL, AnswersJson NVARCHAR(MAX) NULL, SubmittedAt DATETIME2 NOT NULL DEFAULT SYSDATETIME(),"
                + "CONSTRAINT UQ_AssessmentAttempts UNIQUE (AssessmentId, UserId))");
        ready = true;
    }

    // ── Droits ───────────────────────────────────────────────────────────────────────

    static boolean isQa(AuthenticatedUser r) {
        if (r == null) return false;
        String role = r.role() == null ? "" : r.role().toLowerCase(Locale.ROOT);
        String svc = r.service() == null ? "" : r.service().toLowerCase(Locale.ROOT).replace('_', ' ');
        return role.equals("admin") || svc.equals("quality assurance") || svc.equals("superviseur qa") || svc.equals("formateur");
    }

    private static boolean isTeamLeader(AuthenticatedUser r) {
        return r != null && "team_leader".equalsIgnoreCase(r.role());
    }

    private static boolean isOversight(AuthenticatedUser r) {
        String role = r == null || r.role() == null ? "" : r.role().toLowerCase(Locale.ROOT);
        return role.equals("supervisor") || role.equals("rh");
    }

    private void requireQa(AuthenticatedUser r) {
        if (!isQa(r)) throw ApiException.forbidden("Seule la Quality Assurance (ou l'administrateur) programme les évaluations.");
    }

    private User me(AuthenticatedUser r) {
        return users.findFirstByUsernameIgnoreCase(r.username()).orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
    }

    // ── Équipes ──────────────────────────────────────────────────────────────────────

    private List<String> serviceCodes(User u) {
        return services.findServicesByUserId(u.getId()).stream()
                .filter(a -> a.getService() != null && a.getService().getCode() != null)
                .map(a -> a.getService().getCode().trim().toUpperCase(Locale.ROOT)).toList();
    }

    /** L'agent fait-il partie de l'équipe visée (ALL = tout agent d'une équipe opérationnelle) ? */
    boolean inTarget(String teamCode, User u, List<String> codes) {
        if (u == null || Boolean.FALSE.equals(u.getAccountEnabled())) return false;
        if (u.getLedTeam() != null && !u.getLedTeam().isBlank()) return false; // un Team Leader suit, il ne passe pas
        if (ALL.equals(teamCode)) return TeamClassifier.classify(u.getActivity(), codes) != TeamClassifier.Team.OTHER;
        return TeamClassifier.belongsTo(teamCode, u.getActivity(), codes);
    }

    /** Team Leaders concernés par l'équipe visée. */
    private boolean leadsTarget(String teamCode, User tl) {
        String led = tl.getLedTeam();
        if (led == null || led.isBlank()) return false;
        if (ALL.equals(teamCode)) return true;
        if (led.equalsIgnoreCase(teamCode)) return true;
        // Team Leader du pôle (Inbound Mail, Outbound) : il suit aussi ses sous-équipes (Rafiki, Réseaux sociaux, Télévente…).
        return TeamClassifier.isChannel(teamCode) && !TeamClassifier.isChannel(led)
                && TeamClassifier.teamOf(teamCode) == TeamClassifier.teamOf(led);
    }

    private List<User> targetAgents(String teamCode) {
        List<User> out = new ArrayList<>();
        for (User u : users.findAll()) if (inTarget(teamCode, u, serviceCodes(u))) out.add(u);
        return out;
    }

    // ── Création (brouillon) ─────────────────────────────────────────────────────────

    @Transactional
    public Assessment createDraft(AuthenticatedUser requester, MultipartFile file, String text, String title, String sourceName,
                                  Integer courseId, int count) {
        requireQa(requester);
        ensureSchema();
        QuizGenerationService.DraftSet set = generator.drafts(file, text, count);
        String t = title == null || title.isBlank() ? (sourceName != null && !sourceName.isBlank() ? stripExt(sourceName) : "Évaluation") : title.trim();
        if (t.length() > 200) t = t.substring(0, 200);
        LocalDate today = LocalDate.now();
        Integer id = jdbc.queryForObject("SET NOCOUNT ON; INSERT INTO dbo.Assessments (Title, TeamCode, StartsOn, EndsOn, PassScore, Status, CourseId, SourceName, GeneratedBy, CreatedBy)"
                        + " VALUES (?, 'ALL', ?, ?, 70, 'DRAFT', ?, ?, ?, ?); SELECT CAST(SCOPE_IDENTITY() AS INT)", Integer.class,
                t, java.sql.Date.valueOf(today), java.sql.Date.valueOf(today.plusDays(14)), courseId, cut(sourceName, 260), set.source(), requester.username());
        int pos = 0;
        for (QuizGenerationService.Draft d : set.drafts()) {
            jdbc.update("INSERT INTO dbo.AssessmentQuestions (AssessmentId, Position, QuestionText, OptionsJson, CorrectIndex, Explanation) VALUES (?, ?, ?, ?, ?, ?)",
                    id, ++pos, cut(d.question(), 1000), toJson(d.options()), d.correct(), cut(d.explanation(), 1000));
        }
        log.info("[ÉVALUATION] Brouillon #{} « {} » : {} question(s) ({}) par {}", id, t, pos, set.source(), requester.username());
        return get(id, true);
    }

    /** Questions relues par la QA : modification ou suppression avant publication. */
    @Transactional
    public Assessment updateQuestion(AuthenticatedUser requester, int assessmentId, int questionId, String text, List<String> options, Integer correct) {
        requireQa(requester);
        ensureSchema();
        if (text == null || text.isBlank() || options == null || options.size() < 2 || correct == null || correct < 0 || correct >= options.size()) {
            throw ApiException.badRequest("Question incomplète : texte, au moins 2 réponses et la bonne réponse.");
        }
        jdbc.update("UPDATE dbo.AssessmentQuestions SET QuestionText = ?, OptionsJson = ?, CorrectIndex = ? WHERE Id = ? AND AssessmentId = ?",
                cut(text.trim(), 1000), toJson(options), correct, questionId, assessmentId);
        return get(assessmentId, true);
    }

    @Transactional
    public Assessment deleteQuestion(AuthenticatedUser requester, int assessmentId, int questionId) {
        requireQa(requester);
        ensureSchema();
        jdbc.update("DELETE FROM dbo.AssessmentQuestions WHERE Id = ? AND AssessmentId = ?", questionId, assessmentId);
        return get(assessmentId, true);
    }

    // ── Publication (« Terminer ») ───────────────────────────────────────────────────

    @Transactional
    public Assessment publish(AuthenticatedUser requester, int id, String title, String teamCode, LocalDate startsOn, LocalDate endsOn, Integer passScore) {
        requireQa(requester);
        ensureSchema();
        Assessment a = get(id, true);
        String team = teamCode == null ? "" : teamCode.trim().toUpperCase(Locale.ROOT);
        if (!TEAMS.contains(team)) throw ApiException.badRequest("Choisissez l'équipe concernée par l'évaluation.");
        if (startsOn == null || endsOn == null || endsOn.isBefore(startsOn)) throw ApiException.badRequest("Choisissez la période (date de fin après la date de début).");
        if (a.questionCount() < 1) throw ApiException.badRequest("L'évaluation n'a aucune question.");
        int pass = passScore == null ? 70 : Math.max(1, Math.min(100, passScore));
        String t = title == null || title.isBlank() ? a.title() : cut(title.trim(), 200);
        jdbc.update("UPDATE dbo.Assessments SET Title = ?, TeamCode = ?, StartsOn = ?, EndsOn = ?, PassScore = ?, Status = 'PUBLISHED', PublishedAt = SYSDATETIME() WHERE Id = ?",
                t, team, java.sql.Date.valueOf(startsOn), java.sql.Date.valueOf(endsOn), pass, id);
        notifyPublished(id, t, team, startsOn, endsOn, a.questionCount(), pass);
        return get(id, true);
    }

    private void notifyPublished(int id, String title, String team, LocalDate from, LocalDate to, int questions, int pass) {
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String period = "du " + from.format(f) + " au " + to.format(f);
        String agentMsg = "📝 Nouvelle évaluation « " + title + " » (" + questions + " questions, réussite à " + pass + " %) à passer " + period
                + " : onglet Évaluation → Évaluations programmées.";
        int agents = 0, leaders = 0;
        for (User u : users.findAll()) {
            List<String> codes = serviceCodes(u);
            if (inTarget(team, u, codes)) { notify(u, agentMsg); agents++; }
            else if (!Boolean.FALSE.equals(u.getAccountEnabled()) && leadsTarget(team, u)) {
                notify(u, "📝 Évaluation « " + title + " » programmée pour votre équipe " + period + " : suivez les résultats dans votre portail "
                        + "(onglet Évaluations QA) et dans Performances au fur et à mesure.");
                leaders++;
            }
        }
        log.info("[ÉVALUATION] #{} publiée pour {} : {} agent(s) et {} Team Leader(s) prévenus", id, team, agents, leaders);
    }

    private void notify(User u, String content) {
        try {
            notifications.save(RccNotification.builder().targetUser(u).content(content).isRead(false).build());
        } catch (RuntimeException e) {
            log.debug("[ÉVALUATION] notification non enregistrée : {}", e.getMessage());
        }
    }

    @Transactional
    public void delete(AuthenticatedUser requester, int id) {
        requireQa(requester);
        ensureSchema();
        jdbc.update("DELETE FROM dbo.AssessmentAttempts WHERE AssessmentId = ?", id);
        jdbc.update("DELETE FROM dbo.AssessmentQuestions WHERE AssessmentId = ?", id);
        jdbc.update("DELETE FROM dbo.Assessments WHERE Id = ?", id);
    }

    // ── Lecture ──────────────────────────────────────────────────────────────────────

    /** QA : toutes ; Team Leader : celles de son équipe ; superviseur / RH : toutes (lecture). */
    @Transactional(readOnly = true)
    public List<Assessment> list(AuthenticatedUser requester) {
        ensureSchema();
        List<Assessment> all = jdbc.query("SELECT Id FROM dbo.Assessments ORDER BY CreatedAt DESC", (rs, i) -> rs.getInt(1)).stream()
                .map(id -> get(id, false)).toList();
        if (isQa(requester) || isOversight(requester)) return all;
        if (isTeamLeader(requester)) {
            User tl = me(requester);
            return all.stream().filter(a -> !"DRAFT".equals(a.status()) && leadsTarget(a.teamCode(), tl)).toList();
        }
        throw ApiException.forbidden("Accès réservé à la QA, aux Team Leaders et à l'encadrement.");
    }

    @Transactional(readOnly = true)
    public Assessment getForManager(AuthenticatedUser requester, int id) {
        ensureSchema();
        Assessment a = get(id, true);
        if (isQa(requester) || isOversight(requester)) return a;
        if (isTeamLeader(requester) && leadsTarget(a.teamCode(), me(requester))) return a;
        throw ApiException.forbidden("Évaluation hors de votre périmètre.");
    }

    /** Agent : évaluations publiées pour son équipe (en cours, à venir ou passées). */
    @Transactional(readOnly = true)
    public List<MyAssessment> mine(AuthenticatedUser requester) {
        ensureSchema();
        User u = me(requester);
        List<String> codes = serviceCodes(u);
        LocalDate today = LocalDate.now();
        List<MyAssessment> out = new ArrayList<>();
        for (Integer id : jdbc.query("SELECT Id FROM dbo.Assessments WHERE Status = 'PUBLISHED' ORDER BY EndsOn ASC", (rs, i) -> rs.getInt(1))) {
            Assessment a = get(id, false);
            if (!inTarget(a.teamCode(), u, codes)) continue;
            var attempt = jdbc.queryForList("SELECT Score, SubmittedAt FROM dbo.AssessmentAttempts WHERE AssessmentId = ? AND UserId = ?", id, u.getId());
            boolean done = !attempt.isEmpty();
            Integer score = done ? ((Number) attempt.get(0).get("Score")).intValue() : null;
            LocalDateTime at = done ? ((Timestamp) attempt.get(0).get("SubmittedAt")).toLocalDateTime() : null;
            boolean open = !today.isBefore(a.startsOn()) && !today.isAfter(a.endsOn());
            out.add(new MyAssessment(a, open, done, score, done ? score >= a.passScore() : null, at));
        }
        return out;
    }

    /** Évaluation liée à un cours (redirection de l'agent après sa formation). */
    @Transactional(readOnly = true)
    public Optional<Integer> forCourse(int courseId) {
        ensureSchema();
        return jdbc.query("SELECT TOP 1 Id FROM dbo.Assessments WHERE CourseId = ? AND Status = 'PUBLISHED' ORDER BY CreatedAt DESC",
                (rs, i) -> rs.getInt(1), courseId).stream().findFirst();
    }

    /** Questions à passer (sans les bonnes réponses), si l'agent est concerné et que la période est ouverte. */
    @Transactional(readOnly = true)
    public Assessment take(AuthenticatedUser requester, int id) {
        ensureSchema();
        User u = me(requester);
        Assessment a = get(id, true);
        checkCanTake(a, u);
        List<Question> blind = a.questions().stream().map(q -> new Question(q.id(), q.position(), q.text(), q.options(), null, null)).toList();
        return copy(a, blind);
    }

    private void checkCanTake(Assessment a, User u) {
        if (!"PUBLISHED".equals(a.status())) throw ApiException.badRequest("Cette évaluation n'est pas encore ouverte.");
        if (!inTarget(a.teamCode(), u, serviceCodes(u))) throw ApiException.forbidden("Cette évaluation concerne une autre équipe.");
        LocalDate today = LocalDate.now();
        if (today.isBefore(a.startsOn())) throw ApiException.badRequest("Cette évaluation ouvre le " + a.startsOn() + ".");
        if (today.isAfter(a.endsOn())) throw ApiException.badRequest("La période de cette évaluation est terminée.");
        Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.AssessmentAttempts WHERE AssessmentId = ? AND UserId = ?", Integer.class, a.id(), u.getId());
        if (done != null && done > 0) throw ApiException.badRequest("Vous avez déjà passé cette évaluation.");
    }

    @Transactional
    public SubmitResult submit(AuthenticatedUser requester, int id, Map<String, Integer> answers) {
        ensureSchema();
        User u = me(requester);
        Assessment a = get(id, true);
        checkCanTake(a, u);
        int correct = 0;
        List<Map<String, Object>> review = new ArrayList<>();
        for (Question q : a.questions()) {
            Integer given = answers == null ? null : answers.get(String.valueOf(q.id()));
            boolean ok = given != null && given.equals(q.correct());
            if (ok) correct++;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("questionId", q.id()); r.put("given", given); r.put("correct", q.correct()); r.put("ok", ok); r.put("explanation", q.explanation());
            review.add(r);
        }
        int total = a.questions().size();
        int score = total == 0 ? 0 : Math.round(correct * 100f / total);
        jdbc.update("INSERT INTO dbo.AssessmentAttempts (AssessmentId, UserId, Score, Correct, Total, AnswersJson) VALUES (?, ?, ?, ?, ?, ?)",
                id, u.getId(), score, correct, total, toJson(answers == null ? Map.of() : answers));
        boolean passed = score >= a.passScore();
        // Le Team Leader de l'agent voit le résultat arriver.
        String who = u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername();
        for (User tl : users.findAll()) {
            if (tl.getLedTeam() == null || tl.getLedTeam().isBlank()) continue;
            if (TeamClassifier.belongsTo(tl.getLedTeam(), u.getActivity(), serviceCodes(u))) {
                notify(tl, (passed ? "✅ " : "⚠️ ") + who + " a passé l'évaluation « " + a.title() + " » : " + score + " % (" + correct + "/" + total + ").");
            }
        }
        return new SubmitResult(score, correct, total, passed, a.passScore(), review);
    }

    @Transactional(readOnly = true)
    public Results results(AuthenticatedUser requester, int id) {
        Assessment a = getForManager(requester, id);
        User tl = isTeamLeader(requester) ? me(requester) : null;
        List<Result> rows = new ArrayList<>();
        Set<Long> doneIds = new HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT UserId, Score, Correct, Total, SubmittedAt FROM dbo.AssessmentAttempts WHERE AssessmentId = ? ORDER BY Score DESC", id)) {
            long uid = ((Number) r.get("UserId")).longValue();
            User u = users.findById(uid).orElse(null);
            if (u == null) continue;
            if (tl != null && !TeamClassifier.belongsTo(tl.getLedTeam(), u.getActivity(), serviceCodes(u))) continue;
            doneIds.add(uid);
            int score = ((Number) r.get("Score")).intValue();
            rows.add(new Result(uid, u.getUsername(), u.getName(), u.getActivity(), score, ((Number) r.get("Correct")).intValue(),
                    ((Number) r.get("Total")).intValue(), score >= a.passScore(), ((Timestamp) r.get("SubmittedAt")).toLocalDateTime()));
        }
        List<String> notYet = new ArrayList<>();
        int expected = 0;
        for (User u : targetAgents(a.teamCode())) {
            if (tl != null && !TeamClassifier.belongsTo(tl.getLedTeam(), u.getActivity(), serviceCodes(u))) continue;
            expected++;
            if (!doneIds.contains(u.getId())) notYet.add(u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername());
        }
        notYet.sort(String.CASE_INSENSITIVE_ORDER);
        return new Results(a, rows, notYet, expected);
    }

    /** Résultats d'un agent (Ma Performance). */
    @Transactional(readOnly = true)
    public List<MyAssessment> myResults(AuthenticatedUser requester) {
        return mine(requester).stream().filter(MyAssessment::done).toList();
    }

    // ── Outils ───────────────────────────────────────────────────────────────────────

    private Assessment get(int id, boolean withQuestions) {
        var rows = jdbc.queryForList("SELECT * FROM dbo.Assessments WHERE Id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("Évaluation introuvable.");
        Map<String, Object> r = rows.get(0);
        List<Question> qs = jdbc.query("SELECT Id, Position, QuestionText, OptionsJson, CorrectIndex, Explanation FROM dbo.AssessmentQuestions WHERE AssessmentId = ? ORDER BY Position, Id",
                (rs, i) -> new Question(rs.getInt("Id"), rs.getInt("Position"), rs.getString("QuestionText"), fromJson(rs.getString("OptionsJson")),
                        rs.getInt("CorrectIndex"), rs.getString("Explanation")), id);
        var stats = jdbc.queryForMap("SELECT COUNT(*) AS N, AVG(CAST(Score AS FLOAT)) AS Avg FROM dbo.AssessmentAttempts WHERE AssessmentId = ?", id);
        int pass = ((Number) r.get("PassScore")).intValue();
        Integer passed = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.AssessmentAttempts WHERE AssessmentId = ? AND Score >= ?", Integer.class, id, pass);
        String team = (String) r.get("TeamCode");
        Object created = r.get("CreatedAt");
        return new Assessment(id, (String) r.get("Title"), (String) r.get("Description"), team, TEAM_LABELS.getOrDefault(team, team),
                toDate(r.get("StartsOn")), toDate(r.get("EndsOn")), pass, (String) r.get("Status"),
                r.get("CourseId") == null ? null : ((Number) r.get("CourseId")).intValue(), (String) r.get("SourceName"), (String) r.get("GeneratedBy"),
                (String) r.get("CreatedBy"), created instanceof Timestamp t ? t.toLocalDateTime() : null, qs.size(),
                ((Number) stats.get("N")).intValue(), passed == null ? 0 : passed,
                stats.get("Avg") == null ? null : Math.round(((Number) stats.get("Avg")).doubleValue() * 10) / 10.0,
                withQuestions ? qs : List.of());
    }

    private static Assessment copy(Assessment a, List<Question> qs) {
        return new Assessment(a.id(), a.title(), a.description(), a.teamCode(), a.teamLabel(), a.startsOn(), a.endsOn(), a.passScore(), a.status(),
                a.courseId(), a.sourceName(), a.generatedBy(), a.createdBy(), a.createdAt(), a.questionCount(), a.attempts(), a.passed(),
                a.averageScore(), qs);
    }

    private static LocalDate toDate(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof Timestamp t) return t.toLocalDateTime().toLocalDate();
        return o == null ? null : LocalDate.parse(o.toString().substring(0, 10));
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }

    private List<String> fromJson(String s) {
        try { return json.readValue(s, new TypeReference<List<String>>() {}); } catch (Exception e) { return List.of(); }
    }

    private static String cut(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }

    private static String stripExt(String n) {
        String base = n.replaceAll("^.*[/\\\\]", "");
        return base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : base;
    }
}

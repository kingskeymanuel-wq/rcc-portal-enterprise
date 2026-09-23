package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.TrainingJourneyDtos.Activity;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.Badge;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.Certificate;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.Eligible;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.LeaderboardEntry;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.QaOverview;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.Summary;
import com.ecobank.rccportal.dto.TrainingJourneyDtos.Verification;
import com.ecobank.rccportal.model.Course;
import com.ecobank.rccportal.model.CourseAttempt;
import com.ecobank.rccportal.model.Team;
import com.ecobank.rccportal.model.TrainingCertificate;
import com.ecobank.rccportal.model.TrainingLesson;
import com.ecobank.rccportal.model.TrainingProgress;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.CourseAttemptRepository;
import com.ecobank.rccportal.repository.CourseRepository;
import com.ecobank.rccportal.repository.TeamRepository;
import com.ecobank.rccportal.repository.TrainingCertificateRepository;
import com.ecobank.rccportal.repository.TrainingLessonRepository;
import com.ecobank.rccportal.repository.TrainingProgressRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Espace Formation sur le modèle EduFun : l'agent progresse (XP, niveaux, badges, série de jours,
 * classement d'équipe) à partir de ce qu'il fait RÉELLEMENT dans le portail — leçons terminées
 * (TrainingProgress), cours et évaluations soumis (CourseAttempt), certificats validés.
 * Rien n'est saisi à la main : tout est recalculé à chaque appel, donc toujours synchronisé
 * avec la vue QA.
 *
 * Contrôle QA : les certificats sont DEMANDÉS par l'agent (éligibilité vérifiée ici) puis
 * validés, refusés ou révoqués par QA ; seul un certificat validé reçoit un numéro vérifiable.
 */
@Service
public class TrainingJourneyService {

    // Barème XP (affiché aussi dans l'interface — garder en phase avec formation.js).
    static final int XP_LESSON = 20;
    static final int XP_SELF_ASSESSMENT = 30;
    static final int XP_QUIZ_PASSED = 50;
    static final int XP_QUIZ_PERFECT_BONUS = 20;
    static final int XP_QUIZ_FAILED = 10;
    static final int XP_CERTIFICATE = 100;
    /** Centre d'Évaluation : partie jouée (plafonnée par jour), évaluation notée réussie / tentée. */
    static final int XP_GAME_PLAY = 5;
    static final int XP_GAME_PLAY_DAILY_CAP = 25;
    static final int XP_GAME_EVAL_PASSED = 40;
    static final int XP_GAME_EVAL_TRIED = 10;

    /** Paliers de niveau (XP minimum → nom). */
    static final int[] LEVEL_FLOORS = {0, 100, 300, 600, 1000, 1600};
    static final String[] LEVEL_NAMES = {"Découverte", "Apprenti", "Confirmé", "Expert", "Maître", "Légende"};

    private static final String ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final CourseRepository courseRepository;
    private final CourseAttemptRepository attemptRepository;
    private final TrainingProgressRepository progressRepository;
    private final TrainingLessonRepository lessonRepository;
    private final TrainingCertificateRepository certificateRepository;
    private final com.ecobank.rccportal.repository.GameScoreRepository gameScoreRepository;
    private final com.ecobank.rccportal.repository.GameEvaluationAttemptRepository gameAttemptRepository;
    private final com.ecobank.rccportal.repository.GameDefinitionRepository gameDefinitionRepository;

    public TrainingJourneyService(UserRepository userRepository, TeamRepository teamRepository,
                                  CourseRepository courseRepository, CourseAttemptRepository attemptRepository,
                                  TrainingProgressRepository progressRepository, TrainingLessonRepository lessonRepository,
                                  TrainingCertificateRepository certificateRepository,
                                  com.ecobank.rccportal.repository.GameScoreRepository gameScoreRepository,
                                  com.ecobank.rccportal.repository.GameEvaluationAttemptRepository gameAttemptRepository,
                                  com.ecobank.rccportal.repository.GameDefinitionRepository gameDefinitionRepository) {
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.courseRepository = courseRepository;
        this.attemptRepository = attemptRepository;
        this.progressRepository = progressRepository;
        this.lessonRepository = lessonRepository;
        this.certificateRepository = certificateRepository;
        this.gameScoreRepository = gameScoreRepository;
        this.gameAttemptRepository = gameAttemptRepository;
        this.gameDefinitionRepository = gameDefinitionRepository;
    }

    // ───────────────────────── Tableau de bord agent ─────────────────────────

    @Transactional(readOnly = true)
    public Summary summary(String username) {
        User me = findUser(username);
        Snapshot snap = snapshot();
        Stats mine = snap.stats(me.getId());

        int levelIdx = levelIndex(mine.xp);
        Integer nextXp = levelIdx + 1 < LEVEL_FLOORS.length ? LEVEL_FLOORS[levelIdx + 1] : null;
        String nextName = levelIdx + 1 < LEVEL_NAMES.length ? LEVEL_NAMES[levelIdx + 1] : null;

        // Classement de l'équipe (même activité que l'agent) — top 10 + rang de l'agent.
        List<User> teamMates = me.getActivity() == null ? List.of(me) : snap.users.stream()
                .filter(u -> me.getActivity().equalsIgnoreCase(u.getActivity()))
                .toList();
        List<LeaderboardEntry> ranking = leaderboard(teamMates, snap, me.getId(), Integer.MAX_VALUE);
        Integer myRank = ranking.stream().filter(LeaderboardEntry::me).map(LeaderboardEntry::rank).findFirst().orElse(null);
        List<LeaderboardEntry> top = ranking.stream().limit(10).collect(Collectors.toCollection(ArrayList::new));
        if (myRank != null && myRank > 10) {
            ranking.stream().filter(LeaderboardEntry::me).findFirst().ifPresent(top::add);
        }

        return new Summary(me.getName() != null ? me.getName() : me.getUsername(), teamLabel(me.getActivity(), snap.teams),
                mine.xp, LEVEL_NAMES[levelIdx], LEVEL_FLOORS[levelIdx], nextXp, nextName,
                mine.lessonsCompleted, mine.selfAssessmentsDone, mine.quizzesTaken, mine.quizzesPassed, mine.bestScore,
                streak(mine.activityDays), mine.certificatesIssued, badges(mine), top, myRank,
                eligible(me, snap), mine.gamesPlayed, mine.evaluationsTaken, mine.evaluationsPassed, gameResults(me.getId(), snap));
    }

    /** Par jeu : nombre de parties, meilleur score, et l'évaluation notée du dernier tour ouvert. */
    private List<com.ecobank.rccportal.dto.TrainingJourneyDtos.GameResult> gameResults(Long userId, Snapshot snap) {
        Map<String, List<com.ecobank.rccportal.model.GameScore>> plays = snap.gameScoresByUser.getOrDefault(userId, List.of()).stream()
                .collect(Collectors.groupingBy(com.ecobank.rccportal.model.GameScore::getGameKey));
        Map<String, List<com.ecobank.rccportal.model.GameEvaluationAttempt>> evals = snap.gameAttemptsByUser.getOrDefault(userId, List.of()).stream()
                .collect(Collectors.groupingBy(com.ecobank.rccportal.model.GameEvaluationAttempt::getGameKey));
        java.util.Set<String> keys = new java.util.TreeSet<>(plays.keySet());
        keys.addAll(evals.keySet());
        List<com.ecobank.rccportal.dto.TrainingJourneyDtos.GameResult> out = new ArrayList<>();
        for (String key : keys) {
            List<com.ecobank.rccportal.model.GameScore> p = plays.getOrDefault(key, List.of());
            Integer best = p.stream().map(com.ecobank.rccportal.model.GameScore::getScore).filter(Objects::nonNull).max(Integer::compare).orElse(null);
            LocalDateTime last = p.stream().map(com.ecobank.rccportal.model.GameScore::getPlayedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            Integer evalScore = null, evalAttempts = null;
            Boolean evalPassed = null;
            List<com.ecobank.rccportal.model.GameEvaluationAttempt> e = evals.getOrDefault(key, List.of());
            if (!e.isEmpty()) {
                int round = e.stream().map(a -> a.getEvaluationRound() == null ? 1 : a.getEvaluationRound()).max(Integer::compare).orElse(1);
                List<com.ecobank.rccportal.model.GameEvaluationAttempt> current = e.stream()
                        .filter(a -> (a.getEvaluationRound() == null ? 1 : a.getEvaluationRound()) == round).toList();
                evalAttempts = current.size();
                evalScore = current.stream().map(com.ecobank.rccportal.model.GameEvaluationAttempt::getScore).filter(Objects::nonNull).max(Integer::compare).orElse(null);
                evalPassed = current.stream().anyMatch(TrainingJourneyService::isPassedGameEval);
            }
            out.add(new com.ecobank.rccportal.dto.TrainingJourneyDtos.GameResult(key, p.size(), best, evalScore, evalAttempts, evalPassed, last));
        }
        return out;
    }

    static boolean isPassedGameEval(com.ecobank.rccportal.model.GameEvaluationAttempt a) {
        if (a.getScore() != null && a.getScore() >= CourseService.passThreshold()) return true;
        return a.getTotalCount() != null && a.getTotalCount() > 0 && a.getCorrectCount() != null
                && a.getCorrectCount() * 100 >= CourseService.passThreshold() * a.getTotalCount();
    }

    private List<Badge> badges(Stats s) {
        List<Badge> list = new ArrayList<>();
        list.add(badge("FIRST_STEP", "Premier pas", "bi-flag-fill", "Terminer une première leçon", s.lessonsCompleted, 1));
        list.add(badge("CURIOUS", "Curieux", "bi-lightbulb-fill", "Terminer 5 leçons", s.lessonsCompleted, 5));
        list.add(badge("ASSIDUOUS", "Assidu", "bi-journal-check", "Terminer 20 leçons", s.lessonsCompleted, 20));
        list.add(badge("SELF_AWARE", "Lucide", "bi-person-check-fill", "Réaliser un auto-diagnostic", s.selfAssessmentsDone, 1));
        list.add(badge("FIRST_WIN", "Premier succès", "bi-trophy-fill", "Réussir une évaluation", s.quizzesPassed, 1));
        list.add(badge("QUIZ_MASTER", "As des quiz", "bi-stars", "Réussir 5 évaluations", s.quizzesPassed, 5));
        list.add(badge("PERFECT", "Sans faute", "bi-bullseye", "Obtenir 100 % à une évaluation", s.bestScore >= 100 ? 1 : 0, 1));
        list.add(badge("STREAK", "Régulier", "bi-fire", "Se former 3 jours d'affilée", streak(s.activityDays), 3));
        list.add(badge("PLAYER", "Joueur", "bi-controller", "Jouer 10 parties au Centre d'Évaluation", s.gamesPlayed, 10));
        list.add(badge("EVALUATED", "Évalué", "bi-clipboard-check", "Réussir 3 évaluations du Centre d'Évaluation", s.evaluationsPassed, 3));
        list.add(badge("GRADUATE", "Certifié", "bi-patch-check-fill", "Obtenir un certificat validé par QA", s.certificatesIssued, 1));
        return list;
    }

    private static Badge badge(String code, String label, String icon, String desc, int value, int target) {
        return new Badge(code, label, icon, desc, value >= target, Math.min(value, target), target);
    }

    /** Jours consécutifs (jusqu'à aujourd'hui, ou hier si rien encore aujourd'hui) avec une activité. */
    static int streak(Set<LocalDate> days) {
        LocalDate d = LocalDate.now();
        if (!days.contains(d)) d = d.minusDays(1);
        int n = 0;
        while (days.contains(d)) { n++; d = d.minusDays(1); }
        return n;
    }

    static int levelIndex(int xp) {
        int idx = 0;
        for (int i = 0; i < LEVEL_FLOORS.length; i++) if (xp >= LEVEL_FLOORS[i]) idx = i;
        return idx;
    }

    /** Parcours terminés à 100 % et évaluations notées réussies, sans certificat actif ni demande en cours. */
    private List<Eligible> eligible(User me, Snapshot snap) {
        Set<String> taken = certificateRepository.findByUserIdOrderByRequestedAtDesc(me.getId()).stream()
                .filter(c -> !"REJECTED".equals(c.getStatus()) && !"REVOKED".equals(c.getStatus()))
                .map(c -> c.getSourceType() + ":" + c.getSourceId())
                .collect(Collectors.toSet());
        List<Eligible> list = new ArrayList<>();

        Map<Integer, List<TrainingProgress>> myProgressByFormation = snap.progressByUser.getOrDefault(me.getId(), List.of()).stream()
                .filter(p -> p.getLesson() != null && p.getLesson().getFormation() != null)
                .collect(Collectors.groupingBy(p -> p.getLesson().getFormation().getFormationId()));
        myProgressByFormation.forEach((formationId, progress) -> {
            long total = lessonRepository.countByFormation_FormationId(formationId);
            long done = progress.stream().filter(p -> Boolean.TRUE.equals(p.getCompleted())).count();
            if (total > 0 && done >= total && !taken.contains("FORMATION:" + formationId)) {
                list.add(new Eligible("FORMATION", formationId, progress.get(0).getLesson().getFormation().getTitle(), null));
            }
        });

        for (CourseAttempt a : snap.attemptsByUser.getOrDefault(me.getId(), List.of())) {
            if (isPassedQuiz(a) && !taken.contains("COURSE:" + a.getCourse().getCourseId())) {
                list.add(new Eligible("COURSE", a.getCourse().getCourseId(), a.getCourse().getTitle(), a.getScore()));
            }
        }
        return list;
    }

    // ───────────────────────── Certificats ─────────────────────────

    @Transactional(readOnly = true)
    public List<Certificate> myCertificates(String username) {
        User me = findUser(username);
        Map<String, Team> teams = teamsByCode();
        return certificateRepository.findByUserIdOrderByRequestedAtDesc(me.getId()).stream()
                .map(c -> toCertificate(c, me, teams)).toList();
    }

    @Transactional
    public Certificate requestCertificate(String username, String sourceType, Integer sourceId) {
        User me = findUser(username);
        String type = sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
        Eligible match = eligible(me, snapshot()).stream()
                .filter(e -> e.sourceType().equals(type) && e.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(
                        "Certificat non disponible : terminez tout le parcours (ou réussissez l'évaluation) avant de le demander."));
        TrainingCertificate saved = certificateRepository.save(TrainingCertificate.builder()
                .userId(me.getId()).sourceType(type).sourceId(sourceId)
                .title(match.title()).score(match.score())
                .status("PENDING").requestedAt(LocalDateTime.now())
                .build());
        return toCertificate(saved, me, teamsByCode());
    }

    @Transactional(readOnly = true)
    public List<Certificate> listCertificates(String status) {
        List<TrainingCertificate> list = status == null || status.isBlank()
                ? certificateRepository.findAllByOrderByRequestedAtDesc()
                : certificateRepository.findByStatusOrderByRequestedAtAsc(status.trim().toUpperCase(Locale.ROOT));
        Map<Long, User> users = userRepository.findAllById(list.stream().map(TrainingCertificate::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, Team> teams = teamsByCode();
        return list.stream().map(c -> toCertificate(c, users.get(c.getUserId()), teams)).toList();
    }

    /** QA : ISSUE (valider → numéro), REJECT (refuser, motif obligatoire), REVOKE (retirer un certificat validé). */
    @Transactional
    public Certificate decide(Long id, String action, String note, String decidedBy) {
        TrainingCertificate c = certificateRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Certificat introuvable."));
        String act = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        switch (act) {
            case "ISSUE" -> {
                if (!"PENDING".equals(c.getStatus())) throw ApiException.badRequest("Seule une demande en attente peut être validée.");
                c.setStatus("ISSUED");
                c.setCertificateNumber(newCertificateNumber());
            }
            case "REJECT" -> {
                if (!"PENDING".equals(c.getStatus())) throw ApiException.badRequest("Seule une demande en attente peut être refusée.");
                if (note == null || note.isBlank()) throw ApiException.badRequest("Indiquez le motif du refus.");
                c.setStatus("REJECTED");
            }
            case "REVOKE" -> {
                if (!"ISSUED".equals(c.getStatus())) throw ApiException.badRequest("Seul un certificat validé peut être révoqué.");
                if (note == null || note.isBlank()) throw ApiException.badRequest("Indiquez le motif de la révocation.");
                c.setStatus("REVOKED");
            }
            default -> throw ApiException.badRequest("Action inconnue : ISSUE, REJECT ou REVOKE.");
        }
        c.setDecidedAt(LocalDateTime.now());
        c.setDecidedBy(decidedBy);
        c.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
        TrainingCertificate saved = certificateRepository.save(c);
        return toCertificate(saved, userRepository.findById(saved.getUserId()).orElse(null), teamsByCode());
    }

    @Transactional(readOnly = true)
    public Verification verify(String number) {
        return certificateRepository.findByCertificateNumberIgnoreCase(number == null ? "" : number.trim())
                .map(c -> {
                    User u = userRepository.findById(c.getUserId()).orElse(null);
                    return new Verification("ISSUED".equals(c.getStatus()), c.getStatus(), c.getCertificateNumber(),
                            u != null ? u.getName() : null, c.getTitle(), c.getDecidedAt());
                })
                .orElse(new Verification(false, "UNKNOWN", number, null, null, null));
    }

    private String newCertificateNumber() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder("RCC-").append(LocalDate.now().getYear()).append('-');
            for (int i = 0; i < 6; i++) sb.append(ALPHANUM.charAt(random.nextInt(ALPHANUM.length())));
            if (!certificateRepository.existsByCertificateNumber(sb.toString())) return sb.toString();
        }
        throw new IllegalStateException("Impossible de générer un numéro de certificat unique.");
    }

    // ───────────────────────── Vue QA synchronisée ─────────────────────────

    @Transactional(readOnly = true)
    public QaOverview qaOverview(String teamCode) {
        Snapshot snap = snapshot();
        List<User> scope = snap.users.stream()
                .filter(u -> teamCode == null || teamCode.isBlank() || teamCode.equalsIgnoreCase(u.getActivity()))
                .toList();
        Set<Long> scopeIds = scope.stream().map(User::getId).collect(Collectors.toSet());

        List<Course> courses = courseRepository.findAll();
        int draft = (int) courses.stream().filter(c -> "DRAFT".equals(CourseService.publicationStatusOf(c))).count();
        int published = (int) courses.stream().filter(c -> "PUBLISHED".equals(CourseService.publicationStatusOf(c))).count();
        int archived = courses.size() - draft - published;

        LocalDate today = LocalDate.now();
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        List<Activity> activity = new ArrayList<>();
        Set<Long> activeToday = new HashSet<>();
        int lessonsWeek = 0, passedWeek = 0, failedWeek = 0;

        for (Long uid : scopeIds) {
            User u = snap.usersById.get(uid);
            String team = teamLabel(u.getActivity(), snap.teams);
            for (TrainingProgress p : snap.progressByUser.getOrDefault(uid, List.of())) {
                if (p.getLastActivityAt() != null && p.getLastActivityAt().toLocalDate().equals(today)) activeToday.add(uid);
                if (Boolean.TRUE.equals(p.getCompleted()) && p.getCompletedAt() != null) {
                    if (p.getCompletedAt().isAfter(weekAgo)) lessonsWeek++;
                    TrainingLesson l = p.getLesson();
                    String title = l == null ? "Leçon" : l.getTitle() + (l.getFormation() != null ? " — " + l.getFormation().getTitle() : "");
                    activity.add(new Activity(p.getCompletedAt(), displayName(u), team, "LESSON", title, null, null));
                }
            }
            for (CourseAttempt a : snap.attemptsByUser.getOrDefault(uid, List.of())) {
                if (a.getCompletedAt() == null || !"DONE".equals(a.getStatus())) continue;
                if (a.getCompletedAt().toLocalDate().equals(today)) activeToday.add(uid);
                boolean quiz = "STANDARD".equals(a.getCourse().getType());
                Boolean passed = quiz ? (a.getScore() != null && a.getScore() >= CourseService.passThreshold()) : null;
                if (quiz && a.getCompletedAt().isAfter(weekAgo)) {
                    if (Boolean.TRUE.equals(passed)) passedWeek++; else failedWeek++;
                }
                activity.add(new Activity(a.getCompletedAt(), displayName(u), team, quiz ? "QUIZ" : "SELF_ASSESSMENT",
                        a.getCourse().getTitle(), a.getScore(), passed));
            }
        }
        for (Long uid : scopeIds) {
            User u = snap.usersById.get(uid);
            for (com.ecobank.rccportal.model.GameEvaluationAttempt a : snap.gameAttemptsByUser.getOrDefault(uid, List.of())) {
                if (a.getCreatedAt() == null) continue;
                if (a.getCreatedAt().toLocalDate().equals(today)) activeToday.add(uid);
                boolean passed = isPassedGameEval(a);
                if (a.getCreatedAt().isAfter(weekAgo)) { if (passed) passedWeek++; else failedWeek++; }
                activity.add(new Activity(a.getCreatedAt(), displayName(u), teamLabel(u.getActivity(), snap.teams), "GAME",
                        snap.gameTitles.getOrDefault(a.getGameKey(), a.getGameKey()), a.getScore(), passed));
            }
            for (com.ecobank.rccportal.model.GameScore g : snap.gameScoresByUser.getOrDefault(uid, List.of())) {
                if (g.getPlayedAt() != null && g.getPlayedAt().toLocalDate().equals(today)) activeToday.add(uid);
            }
        }
        activity.sort(Comparator.comparing(Activity::at).reversed());

        int pending = (int) snap.certificates.stream().filter(c -> "PENDING".equals(c.getStatus())
                && scopeIds.contains(c.getUserId())).count();

        return new QaOverview(LocalDateTime.now(), pending, draft, published, archived, activeToday.size(),
                lessonsWeek, passedWeek, failedWeek, activity.stream().limit(30).toList(),
                leaderboard(scope, snap, null, 15));
    }

    // ───────────────────────── Calcul commun ─────────────────────────

    private List<LeaderboardEntry> leaderboard(List<User> users, Snapshot snap, Long meId, int limit) {
        List<User> sorted = users.stream()
                .sorted(Comparator.comparingInt((User u) -> snap.stats(u.getId()).xp).reversed()
                        .thenComparing(u -> displayName(u), String.CASE_INSENSITIVE_ORDER))
                .filter(u -> snap.stats(u.getId()).xp > 0 || Objects.equals(u.getId(), meId))
                .toList();
        List<LeaderboardEntry> out = new ArrayList<>();
        for (int i = 0; i < sorted.size() && out.size() < limit; i++) {
            User u = sorted.get(i);
            int xp = snap.stats(u.getId()).xp;
            out.add(new LeaderboardEntry(i + 1, displayName(u), teamLabel(u.getActivity(), snap.teams), xp,
                    LEVEL_NAMES[levelIndex(xp)], Objects.equals(u.getId(), meId)));
        }
        return out;
    }

    static boolean isPassedQuiz(CourseAttempt a) {
        return "DONE".equals(a.getStatus()) && a.getCourse() != null && "STANDARD".equals(a.getCourse().getType())
                && a.getScore() != null && a.getScore() >= CourseService.passThreshold();
    }

    /** XP d'une tentative soumise (voir barème en tête de classe). */
    static int attemptXp(CourseAttempt a) {
        if (!"DONE".equals(a.getStatus()) || a.getCourse() == null) return 0;
        if (!"STANDARD".equals(a.getCourse().getType())) return XP_SELF_ASSESSMENT;
        if (a.getScore() == null) return 0;
        if (a.getScore() >= CourseService.passThreshold()) {
            return XP_QUIZ_PASSED + (a.getScore() >= 100 ? XP_QUIZ_PERFECT_BONUS : 0);
        }
        return XP_QUIZ_FAILED;
    }

    private Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.users = userRepository.findAll();
        s.usersById = s.users.stream().collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        s.teams = teamsByCode();
        s.progressByUser = progressRepository.findAll().stream()
                .filter(p -> p.getUserId() != null)
                .collect(Collectors.groupingBy(TrainingProgress::getUserId));
        s.attemptsByUser = attemptRepository.findAll().stream()
                .filter(a -> a.getUser() != null)
                .collect(Collectors.groupingBy(a -> a.getUser().getId()));
        s.certificates = certificateRepository.findAll();
        s.gameScoresByUser = gameScoreRepository.findAll().stream().filter(g -> g.getUserId() != null)
                .collect(Collectors.groupingBy(com.ecobank.rccportal.model.GameScore::getUserId));
        s.gameAttemptsByUser = gameAttemptRepository.findAll().stream().filter(g -> g.getUserId() != null)
                .collect(Collectors.groupingBy(com.ecobank.rccportal.model.GameEvaluationAttempt::getUserId));
        s.gameTitles = gameDefinitionRepository.findAll().stream().filter(g -> g.getGameKey() != null)
                .collect(Collectors.toMap(com.ecobank.rccportal.model.GameDefinition::getGameKey,
                        g -> g.getTitle() != null ? g.getTitle() : g.getGameKey(), (a, b) -> a));
        s.issuedByUser = s.certificates.stream().filter(c -> "ISSUED".equals(c.getStatus()))
                .collect(Collectors.groupingBy(TrainingCertificate::getUserId, Collectors.counting()));
        return s;
    }

    private final class Snapshot {
        List<User> users;
        Map<Long, User> usersById;
        Map<String, Team> teams;
        Map<Long, List<TrainingProgress>> progressByUser;
        Map<Long, List<CourseAttempt>> attemptsByUser;
        List<TrainingCertificate> certificates;
        Map<Long, Long> issuedByUser;
        Map<Long, List<com.ecobank.rccportal.model.GameScore>> gameScoresByUser = Map.of();
        Map<Long, List<com.ecobank.rccportal.model.GameEvaluationAttempt>> gameAttemptsByUser = Map.of();
        Map<String, String> gameTitles = Map.of();
        private final Map<Long, Stats> cache = new HashMap<>();

        Stats stats(Long userId) {
            return cache.computeIfAbsent(userId, id -> {
                Stats st = new Stats();
                for (TrainingProgress p : progressByUser.getOrDefault(id, List.of())) {
                    if (Boolean.TRUE.equals(p.getCompleted())) {
                        st.lessonsCompleted++;
                        if (p.getCompletedAt() != null) st.activityDays.add(p.getCompletedAt().toLocalDate());
                    }
                    if (p.getLastActivityAt() != null) st.activityDays.add(p.getLastActivityAt().toLocalDate());
                }
                for (CourseAttempt a : attemptsByUser.getOrDefault(id, List.of())) {
                    if (!"DONE".equals(a.getStatus()) || a.getCourse() == null) continue;
                    if (a.getCompletedAt() != null) st.activityDays.add(a.getCompletedAt().toLocalDate());
                    if ("STANDARD".equals(a.getCourse().getType())) {
                        st.quizzesTaken++;
                        if (isPassedQuiz(a)) st.quizzesPassed++;
                        if (a.getScore() != null) st.bestScore = Math.max(st.bestScore, a.getScore());
                    } else {
                        st.selfAssessmentsDone++;
                    }
                    st.xp += attemptXp(a);
                }
                // Centre d'Évaluation : parties (XP plafonnée par jour) et évaluations notées (une fois par jeu et par tour).
                Map<LocalDate, Integer> playsPerDay = new HashMap<>();
                for (com.ecobank.rccportal.model.GameScore g : gameScoresByUser.getOrDefault(id, List.of())) {
                    st.gamesPlayed++;
                    LocalDate day = g.getPlayedAt() != null ? g.getPlayedAt().toLocalDate() : LocalDate.MIN;
                    if (g.getPlayedAt() != null) st.activityDays.add(day);
                    playsPerDay.merge(day, 1, Integer::sum);
                }
                playsPerDay.values().forEach(n -> st.xp += Math.min(n * XP_GAME_PLAY, XP_GAME_PLAY_DAILY_CAP));
                Map<String, Boolean> evalOutcome = new HashMap<>();
                for (com.ecobank.rccportal.model.GameEvaluationAttempt a : gameAttemptsByUser.getOrDefault(id, List.of())) {
                    String k = a.getGameKey() + "#" + (a.getEvaluationRound() == null ? 1 : a.getEvaluationRound());
                    evalOutcome.merge(k, isPassedGameEval(a), Boolean::logicalOr);
                    if (a.getCreatedAt() != null) st.activityDays.add(a.getCreatedAt().toLocalDate());
                }
                st.evaluationsTaken = evalOutcome.size();
                st.evaluationsPassed = (int) evalOutcome.values().stream().filter(Boolean::booleanValue).count();
                st.xp += st.evaluationsPassed * XP_GAME_EVAL_PASSED + (st.evaluationsTaken - st.evaluationsPassed) * XP_GAME_EVAL_TRIED;

                st.certificatesIssued = issuedByUser.getOrDefault(id, 0L).intValue();
                st.xp += st.lessonsCompleted * XP_LESSON + st.certificatesIssued * XP_CERTIFICATE;
                return st;
            });
        }
    }

    private static final class Stats {
        int xp, lessonsCompleted, selfAssessmentsDone, quizzesTaken, quizzesPassed, bestScore, certificatesIssued,
                gamesPlayed, evaluationsTaken, evaluationsPassed;
        final Set<LocalDate> activityDays = new HashSet<>();
    }

    private Certificate toCertificate(TrainingCertificate c, User holder, Map<String, Team> teams) {
        return new Certificate(c.getId(), c.getCertificateNumber(), holder != null ? displayName(holder) : null,
                holder != null ? teamLabel(holder.getActivity(), teams) : null, c.getSourceType(), c.getSourceId(),
                c.getTitle(), c.getScore(), c.getStatus(), c.getRequestedAt(), c.getDecidedAt(), c.getDecidedBy(),
                c.getDecisionNote());
    }

    private Map<String, Team> teamsByCode() {
        return teamRepository.findAll().stream().filter(t -> t.getCode() != null)
                .collect(Collectors.toMap(t -> t.getCode().toUpperCase(Locale.ROOT), Function.identity(), (a, b) -> a));
    }

    private static String teamLabel(String code, Map<String, Team> teams) {
        if (code == null || code.isBlank()) return null;
        Team t = teams.get(code.toUpperCase(Locale.ROOT));
        return t != null && t.getLabel() != null ? t.getLabel() : code;
    }

    private static String displayName(User u) {
        return u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername();
    }

    private User findUser(String username) {
        if (username == null) throw ApiException.unauthorized("Utilisateur non authentifié.");
        return userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur introuvable."));
    }
}

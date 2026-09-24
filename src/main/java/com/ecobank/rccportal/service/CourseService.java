package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Formation — cours créés par QA/admin, tentatives suivies par agent. Deux
 * types de cours (voir Course.type) : STANDARD (noté, bonne/mauvaise réponse)
 * et SELF_ASSESSMENT (auto-diagnostic à échelle, pas de notion de score).
 */
@Service
public class CourseService {

    private static final List<String> VALID_TYPES = List.of("STANDARD", "SELF_ASSESSMENT");
    private static final List<String> VALID_STATUSES = List.of("TODO", "IN_PROGRESS", "DONE");

    private final CourseRepository courseRepository;
    private final CourseQuestionRepository questionRepository;
    private final CourseAttemptRepository attemptRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final com.ecobank.rccportal.repository.CourseCategoryRepository courseCategoryRepository;
    private final ImageStorageService imageStorageService;
    private final DocumentStorageService documentStorageService;
    private final ObjectMapper objectMapper;

    public CourseService(
            CourseRepository courseRepository,
            CourseQuestionRepository questionRepository,
            CourseAttemptRepository attemptRepository,
            UserRepository userRepository,
            TeamRepository teamRepository,
            com.ecobank.rccportal.repository.CourseCategoryRepository courseCategoryRepository,
            ImageStorageService imageStorageService,
            DocumentStorageService documentStorageService,
            ObjectMapper objectMapper) {
        this.courseRepository = courseRepository;
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.courseCategoryRepository = courseCategoryRepository;
        this.imageStorageService = imageStorageService;
        this.documentStorageService = documentStorageService;
        this.objectMapper = objectMapper;
    }

    /** Upload réel de la vignette — QA ou administrateur (voir CourseController). */
    @Transactional
    public CourseResponse updateCourseImage(Integer courseId, org.springframework.web.multipart.MultipartFile file) {
        Course course = findCourse(courseId);
        course.setImageUrl(imageStorageService.store(file));
        return toResponse(courseRepository.save(course));
    }

    /** Vidéo téléversée depuis le studio de création : stockée comme les vidéos Mon RCC. */
    @Transactional
    public CourseResponse updateCourseVideo(Integer courseId, org.springframework.web.multipart.MultipartFile file) {
        Course course = findCourse(courseId);
        String name = file == null ? null : file.getOriginalFilename();
        String type = file == null ? null : file.getContentType();
        boolean video = (name != null && name.toLowerCase().matches(".*\\.(mp4|m4v|mov|webm)$"))
                || (type != null && type.toLowerCase().startsWith("video/"));
        if (!video) {
            throw ApiException.badRequest("Ce fichier n'est pas une vidéo (formats acceptés : mp4, m4v, mov, webm).");
        }
        course.setVideoUrl(imageStorageService.store(file));
        return toResponse(courseRepository.save(course));
    }

    /** Fichier joint au cours (PDF, Word...) — distinct de la vignette et du lien vidéo. */
    public CourseResponse updateCourseFile(Integer courseId, org.springframework.web.multipart.MultipartFile file) {
        Course course = findCourse(courseId);
        course.setFileUrl(documentStorageService.store(file));
        course.setFileName(file.getOriginalFilename());
        return toResponse(courseRepository.save(course));
    }

    // ── Cours ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CourseResponse> listCourses(AuthenticatedUser requester) {
        List<CourseResponse> all = courseRepository.findAll().stream().map(this::toResponse).toList();
        return filterVisible(all, requester);
    }

    /** QA/admin voient tout ; un agent ne voit que les cours génériques + ceux de sa propre
     * équipe (référentiel de l'onglet Shift, comparé à User.activity — voir ShiftService.forTeam
     * pour le même principe côté planning). */
    private List<CourseResponse> filterVisible(List<CourseResponse> courses, AuthenticatedUser requester) {
        if (requester == null) return courses;
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (isAdmin || isQa || isQaStaff(requester)) return courses;

        // Agent : seulement les cours PUBLIÉS par QA (brouillons et archives invisibles).
        String myTeamCode = currentUserTeamCode(requester.username());
        return courses.stream()
                .filter(c -> "PUBLISHED".equals(c.publicationStatus()))
                .filter(c -> c.teamCode() == null || c.teamCode().equalsIgnoreCase(myTeamCode))
                .toList();
    }

    private String currentUserTeamCode(String username) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElse(null);
        if (user == null) return null;
        return user.getActivity();
    }

    private Team resolveTeam(String teamCode) {
        if (teamCode == null || teamCode.isBlank()) return null;
        return teamRepository.findByCode(teamCode.trim())
                .orElseThrow(() -> ApiException.badRequest("Unknown team: " + teamCode));
    }

    @Transactional
    public CourseResponse createCourse(CreateCourseRequest request, AuthenticatedUser requester) {
        String title = request.title() == null ? "" : request.title().trim();
        if (title.isBlank()) {
            throw ApiException.badRequest("Le titre est obligatoire.");
        }
        String type = request.type() == null ? "" : request.type().toUpperCase();
        if (!VALID_TYPES.contains(type)) {
            throw ApiException.badRequest("type doit être STANDARD ou SELF_ASSESSMENT.");
        }

        User author = userRepository.findFirstByUsernameIgnoreCase(requester.username()).orElse(null);
        Team team = resolveTeam(request.teamCode());

        Course course = courseRepository.save(Course.builder()
                .title(title)
                .description(request.description())
                .content(request.content())
                .type(type)
                .mandatory(Boolean.TRUE.equals(request.mandatory()))
                .videoUrl(blankToNull(request.videoUrl()))
                .team(team)
                .category(blankToNull(request.category()))
                .createdBy(author)
                // Nouveau cours = brouillon : QA le vérifie (questions, vidéo…) puis le publie.
                .publicationStatus("DRAFT")
                .build());

        return toResponse(course);
    }

    /** Mise à jour partielle : chaque champ non nul remplace la valeur existante. */
    @Transactional
    public CourseResponse updateCourse(Integer courseId, CreateCourseRequest request) {
        Course course = findCourse(courseId);

        if (request.title() != null && !request.title().isBlank()) {
            course.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            course.setDescription(request.description());
        }
        if (request.content() != null) {
            course.setContent(request.content());
        }
        if (request.type() != null && !request.type().isBlank()) {
            String type = request.type().toUpperCase();
            if (!VALID_TYPES.contains(type)) {
                throw ApiException.badRequest("type doit être STANDARD ou SELF_ASSESSMENT.");
            }
            course.setType(type);
        }
        if (request.mandatory() != null) {
            course.setMandatory(request.mandatory());
        }
        if (request.videoUrl() != null) {
            course.setVideoUrl(blankToNull(request.videoUrl()));
        }
        if (request.teamCode() != null) {
            // Chaîne vide = retirer l'affectation (redevient un cours générique).
            course.setTeam(request.teamCode().isBlank() ? null : resolveTeam(request.teamCode()));
        }
        if (request.category() != null) {
            course.setCategory(blankToNull(request.category()));
        }

        return toResponse(courseRepository.save(course));
    }

    @Transactional
    public void deleteCourse(Integer courseId) {
        Course course = findCourse(courseId);

        // Sans ça, la suppression échoue silencieusement en erreur générique dès qu'une
        // question ou une tentative existe (contrainte de clé étrangère côté SQL Server).
        List<CourseQuestion> questions = questionRepository.findByCourseOrderByQuestionNumberAsc(course);
        questionRepository.deleteAll(questions);

        List<CourseAttempt> attempts = attemptRepository.findByCourse(course);
        attemptRepository.deleteAll(attempts);

        courseRepository.delete(course);
    }

    /** correctOptionIndex n'est renvoyé que pour QA/admin (isQaOrAdmin=true) — jamais côté agent en train de répondre. */
    @Transactional(readOnly = true)
    public List<CourseQuestionResponse> listQuestions(Integer courseId, boolean isQaOrAdmin) {
        Course course = findCourse(courseId);
        return questionRepository.findByCourseOrderByQuestionNumberAsc(course).stream()
                .map(q -> toQuestionResponse(q, isQaOrAdmin))
                .toList();
    }

    @Transactional
    public CourseQuestionResponse addQuestion(Integer courseId, CreateCourseQuestionRequest request) {
        Course course = findCourse(courseId);

        String questionText = request.questionText() == null ? "" : request.questionText().trim();
        if (questionText.isBlank()) {
            throw ApiException.badRequest("Le texte de la question est obligatoire.");
        }

        if ("STANDARD".equals(course.getType())) {
            if (request.options() == null || request.options().size() < 2) {
                throw ApiException.badRequest("Au moins 2 options sont requises pour un cours STANDARD.");
            }
            if (request.correctOptionIndex() == null
                    || request.correctOptionIndex() < 0
                    || request.correctOptionIndex() >= request.options().size()) {
                throw ApiException.badRequest("correctOptionIndex doit pointer vers une option valide.");
            }
        }

        int nextNumber = questionRepository.findByCourseOrderByQuestionNumberAsc(course).size() + 1;

        CourseQuestion question = questionRepository.save(CourseQuestion.builder()
                .course(course)
                .questionText(questionText)
                .questionNumber(nextNumber)
                .optionsJson(request.options() != null ? toJson(request.options()) : null)
                .correctOptionIndex("STANDARD".equals(course.getType()) ? request.correctOptionIndex() : null)
                .build());

        return toQuestionResponse(question, true);
    }

    @Transactional
    public CourseQuestionResponse updateQuestion(Integer questionId, CreateCourseQuestionRequest request) {
        CourseQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> ApiException.notFound("Question introuvable."));
        Course course = question.getCourse();

        if (request.questionText() != null && !request.questionText().isBlank()) {
            question.setQuestionText(request.questionText().trim());
        }

        if ("STANDARD".equals(course.getType())) {
            if (request.options() != null) {
                if (request.options().size() < 2) {
                    throw ApiException.badRequest("Au moins 2 options sont requises pour un cours STANDARD.");
                }
                question.setOptionsJson(toJson(request.options()));
            }
            if (request.correctOptionIndex() != null) {
                question.setCorrectOptionIndex(request.correctOptionIndex());
            }
        }

        return toQuestionResponse(questionRepository.save(question), true);
    }

    @Transactional
    public void deleteQuestion(Integer questionId) {
        CourseQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> ApiException.notFound("Question introuvable."));
        questionRepository.delete(question);
    }

    // ── Tentatives (agent) ──────────────────────────────────────────────

    private static final int PASS_THRESHOLD = 70;
    private static final int MAX_ATTEMPTS = 2;

    /** Récupère (ou crée, statut TODO) la tentative de l'utilisateur courant pour un cours. */
    @Transactional
    public CourseAttemptResponse getOrCreateAttempt(Integer courseId, AuthenticatedUser requester) {
        Course course = findCourse(courseId);
        User user = findUser(requester);

        CourseAttempt attempt = attemptRepository.findByCourseAndUser(course, user)
                .orElseGet(() -> attemptRepository.save(CourseAttempt.builder()
                        .course(course).user(user).status("TODO").attemptNumber(1).finalized(false).build()));

        if ("TODO".equals(attempt.getStatus())) {
            attempt.setStatus("IN_PROGRESS");
            attemptRepository.save(attempt);
        } else if ("DONE".equals(attempt.getStatus()) && !Boolean.TRUE.equals(attempt.getFinalized())) {
            // Échec non finalisé : ouvre le rattrapage (dernière chance, cf. submitAttempt).
            attempt.setStatus("IN_PROGRESS");
            attempt.setAttemptNumber(Math.min(MAX_ATTEMPTS, attempt.getAttemptNumber() + 1));
            attemptRepository.save(attempt);
        } else if (Boolean.TRUE.equals(attempt.getFinalized())) {
            throw ApiException.badRequest("Ce cours est déjà finalisé (résultat définitif) — aucune nouvelle tentative possible.");
        }

        return toAttemptResponse(attempt);
    }

    @Transactional
    public CourseAttemptResponse submitAttempt(Integer courseId, SubmitCourseAttemptRequest request, AuthenticatedUser requester) {
        Course course = findCourse(courseId);
        User user = findUser(requester);

        CourseAttempt attempt = attemptRepository.findByCourseAndUser(course, user)
                .orElseThrow(() -> ApiException.badRequest("Aucune tentative en cours — chargez d'abord le cours."));

        if (Boolean.TRUE.equals(attempt.getFinalized())) {
            throw ApiException.badRequest("Ce cours est déjà finalisé (résultat définitif) — impossible de resoumettre.");
        }

        // Vidéo obligatoire regardée en entier avant de pouvoir soumettre — même principe que
        // TrainingProgress, appliqué désormais aux Course. Facultatif seulement si aucune vidéo
        // n'est rattachée au cours (voir demande : "la vidéo est d'ailleurs facultatif").
        if (course.getVideoUrl() != null && !course.getVideoUrl().isBlank() && attempt.getVideoWatchedPercent() < 100) {
            throw ApiException.badRequest("Regardez la vidéo jusqu'à la fin avant de soumettre ce cours (" +
                    attempt.getVideoWatchedPercent() + "% visionné).");
        }

        Map<String, Integer> answers = request.answers() != null ? request.answers() : Map.of();

        Integer score = null;
        boolean finalized;
        if ("STANDARD".equals(course.getType())) {
            List<CourseQuestion> questions = questionRepository.findByCourseOrderByQuestionNumberAsc(course);
            long correct = questions.stream()
                    .filter(q -> {
                        Integer given = answers.get(String.valueOf(q.getQuestionId()));
                        return given != null && given.equals(q.getCorrectOptionIndex());
                    })
                    .count();
            score = questions.isEmpty() ? 0 : (int) Math.round((correct * 100.0) / questions.size());
            // Réussi du premier coup, ou 2e tentative déjà consommée : le résultat devient définitif.
            finalized = score >= PASS_THRESHOLD || attempt.getAttemptNumber() >= MAX_ATTEMPTS;
        } else {
            // Auto-diagnostic : pas de notion de réussite/échec, une seule soumission possible.
            finalized = true;
        }

        attempt.setStatus("DONE");
        attempt.setScore(score);
        attempt.setAnswersJson(toJson(answers));
        attempt.setCompletedAt(LocalDateTime.now());
        attempt.setFinalized(finalized);
        attemptRepository.save(attempt);

        return toAttemptResponse(attempt);
    }

    @Transactional(readOnly = true)
    public List<CourseAttemptResponse> listMyAttempts(AuthenticatedUser requester) {
        User user = findUser(requester);
        return attemptRepository.findByUser(user).stream().map(this::toAttemptResponse).toList();
    }

    /** QA/admin — tous les agents et leur résultat pour un cours donné. */
    @Transactional(readOnly = true)
    public List<CourseAttemptResponse> listAttemptsForCourse(Integer courseId) {
        Course course = findCourse(courseId);
        return attemptRepository.findByCourse(course).stream().map(this::toAttemptResponse).toList();
    }

    // ── Utilitaires ──────────────────────────────────────────────────────

    private Course findCourse(Integer courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> ApiException.notFound("Cours introuvable."));
    }

    private User findUser(AuthenticatedUser requester) {
        return userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw ApiException.badRequest("Format de données invalide.");
        }
    }

    private List<String> parseOptions(String optionsJson) {
        if (optionsJson == null) return List.of();
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Integer> parseAnswers(String answersJson) {
        if (answersJson == null) return new HashMap<>();
        try {
            return objectMapper.readValue(answersJson, new TypeReference<Map<String, Integer>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // RUBRIQUES DE FORMATION (avec image — même principe que la Base de connaissances)
    // ══════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.CourseCategoryResponse> listCourseCategories() {
        return courseCategoryRepository.findAllByOrderBySortOrderAsc().stream()
                .map(c -> new com.ecobank.rccportal.dto.CourseCategoryResponse(
                        c.getCourseCategoryId(), c.getTitle(), c.getImageUrl()))
                .toList();
    }

    /** Dépose/remplace l'image d'une rubrique — la crée si elle n'existe pas encore (première
     *  fois qu'on illustre une thématique qui n'existait jusqu'ici qu'en texte libre sur des cours). */
    @Transactional
    public com.ecobank.rccportal.dto.CourseCategoryResponse uploadCategoryImage(String title, org.springframework.web.multipart.MultipartFile file) {
        com.ecobank.rccportal.model.CourseCategory category = courseCategoryRepository.findByTitleIgnoreCase(title)
                .orElseGet(() -> com.ecobank.rccportal.model.CourseCategory.builder().title(title).build());
        category.setImageUrl(imageStorageService.store(file));
        com.ecobank.rccportal.model.CourseCategory saved = courseCategoryRepository.save(category);
        return new com.ecobank.rccportal.dto.CourseCategoryResponse(saved.getCourseCategoryId(), saved.getTitle(), saved.getImageUrl());
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROGRESSION VIDÉO STRICTE — "du début à la fin, sans retour en arrière"
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Rapporte la progression de lecture vidéo pour la tentative en cours. Le pourcentage ne
     * peut jamais REDESCENDRE (protège contre un rechargement de page qui renverrait 0%) —
     * seule la valeur la plus haute jamais atteinte est conservée. seekViolation=true signale
     * une tentative d'avance rapide/retour en arrière détectée côté lecteur — tracée pour
     * QA/audit, jamais bloquante en soi (l'agent peut simplement continuer à regarder).
     */
    @Transactional
    public void updateVideoProgress(Integer courseId, AuthenticatedUser requester, int watchedPercent, boolean seekViolation) {
        Course course = findCourse(courseId);
        User user = findUser(requester);
        CourseAttempt attempt = attemptRepository.findByCourseAndUser(course, user)
                .orElseGet(() -> attemptRepository.save(CourseAttempt.builder()
                        .course(course).user(user).status("IN_PROGRESS")
                        .attemptNumber(1).finalized(false).videoWatchedPercent(0).seekViolationCount(0).build()));

        int clamped = Math.max(0, Math.min(100, watchedPercent));
        if (clamped > attempt.getVideoWatchedPercent()) {
            attempt.setVideoWatchedPercent(clamped);
        }
        if (seekViolation) {
            attempt.setSeekViolationCount(attempt.getSeekViolationCount() + 1);
        }
        attemptRepository.save(attempt);
    }

    static final List<String> PUBLICATION_STATUSES = List.of("DRAFT", "PUBLISHED", "ARCHIVED");

    /** QA : publier / repasser en brouillon / archiver un cours (contrôle de ce que voient les agents). */
    @Transactional
    public CourseResponse updatePublication(Integer courseId, String status, AuthenticatedUser requester) {
        String target = status == null ? "" : status.trim().toUpperCase();
        if (!PUBLICATION_STATUSES.contains(target)) {
            throw ApiException.badRequest("Statut de publication invalide : DRAFT, PUBLISHED ou ARCHIVED.");
        }
        Course course = findCourse(courseId);
        if ("PUBLISHED".equals(target) && "STANDARD".equals(course.getType())
                && questionRepository.findByCourseOrderByQuestionNumberAsc(course).isEmpty()) {
            throw ApiException.badRequest("Ajoutez au moins une question avant de publier une évaluation notée.");
        }
        course.setPublicationStatus(target);
        if ("PUBLISHED".equals(target)) {
            course.setPublishedAt(LocalDateTime.now());
            course.setPublishedBy(requester != null ? (requester.name() != null ? requester.name() : requester.username()) : null);
        }
        return toResponse(courseRepository.save(course));
    }

    static String publicationStatusOf(Course c) {
        return c.getPublicationStatus() == null || c.getPublicationStatus().isBlank() ? "PUBLISHED" : c.getPublicationStatus();
    }

    private static boolean isQaStaff(AuthenticatedUser requester) {
        return requester.service() != null && java.util.Set.of("superviseur qa", "formateur")
                .contains(requester.service().toLowerCase().replace('_', ' '));
    }

    static int passThreshold() { return PASS_THRESHOLD; }

    private CourseResponse toResponse(Course c) {
        int questionCount = questionRepository.findByCourseOrderByQuestionNumberAsc(c).size();
        return new CourseResponse(c.getCourseId(), c.getTitle(), c.getDescription(), c.getContent(),
                c.getType(), c.getMandatory(), c.getVideoUrl(), questionCount,
                c.getTeam() != null ? c.getTeam().getCode() : null,
                c.getTeam() != null ? c.getTeam().getLabel() : null,
                c.getCreatedBy() != null ? c.getCreatedBy().getUsername() : null,
                c.getCreatedBy() != null ? c.getCreatedBy().getName() : null,
                c.getImageUrl(), c.getFileUrl(), c.getFileName(), c.getCategory(),
                publicationStatusOf(c), c.getPublishedAt(), c.getPublishedBy());
    }

    private CourseQuestionResponse toQuestionResponse(CourseQuestion q, boolean includeCorrectAnswer) {
        return new CourseQuestionResponse(q.getQuestionId(), q.getQuestionText(), q.getQuestionNumber(),
                parseOptions(q.getOptionsJson()), includeCorrectAnswer ? q.getCorrectOptionIndex() : null);
    }

    private CourseAttemptResponse toAttemptResponse(CourseAttempt a) {
        String teamCode = a.getUser() != null ? a.getUser().getActivity() : null;
        String teamLabel = teamCode != null
                ? teamRepository.findByCode(teamCode).map(Team::getLabel).orElse(teamCode)
                : null;
        return new CourseAttemptResponse(a.getAttemptId(), a.getCourse().getCourseId(), a.getCourse().getTitle(),
                a.getUser().getUsername(), a.getUser().getName(), a.getStatus(), a.getScore(),
                parseAnswers(a.getAnswersJson()), a.getCompletedAt(),
                a.getAttemptNumber(), Boolean.TRUE.equals(a.getFinalized()), teamCode, teamLabel);
    }
}
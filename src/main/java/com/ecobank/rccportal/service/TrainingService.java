package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.TrainingFormation;
import com.ecobank.rccportal.model.TrainingLesson;
import com.ecobank.rccportal.model.TrainingProgress;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.TrainingFormationRepository;
import com.ecobank.rccportal.repository.TrainingLessonRepository;
import com.ecobank.rccportal.repository.TrainingProgressRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Programmation des formations (jour/semaine/mois) + suivi reel et non
 * falsifiable du parcours de chaque agent (scroll + video plafonnee en vitesse),
 * avec agregation equipe/individu pour le tableau de bord QA.
 *
 * Principe anti-triche : le pourcentage stocke ne peut jamais redescendre, et
 * toute lecon n'est marquee "completee" que lorsque scroll ET video (si video
 * presente) atteignent le seuil configure sur la formation (95% par defaut).
 * Un agent qui ouvre la lecon puis change d'onglet sans derouler le contenu
 * n'obtient donc jamais 100%.
 */
@Service
public class TrainingService {

    private final TrainingFormationRepository formationRepository;
    private final TrainingLessonRepository lessonRepository;
    private final TrainingProgressRepository progressRepository;
    private final UserRepository userRepository;

    public TrainingService(TrainingFormationRepository formationRepository,
                            TrainingLessonRepository lessonRepository,
                            TrainingProgressRepository progressRepository,
                            UserRepository userRepository) {
        this.formationRepository = formationRepository;
        this.lessonRepository = lessonRepository;
        this.progressRepository = progressRepository;
        this.userRepository = userRepository;
    }

    // ===================== FORMATIONS =====================

    @Transactional(readOnly = true)
    public List<TrainingFormationResponse> listFormations(Long currentUserId, String currentUserTeam) {
        try {
            List<TrainingFormation> all = formationRepository.findAllByOrderByScheduledDateAscScheduledTimeAsc();
            return all.stream()
                    .filter(f -> f.getTargetTeam() == null || f.getTargetTeam().isBlank()
                            || currentUserTeam == null || f.getTargetTeam().equalsIgnoreCase(currentUserTeam))
                    .map(f -> toResponse(f, currentUserId))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional
    public TrainingFormationResponse createFormation(TrainingFormationRequest request, Long requesterId) {
        validateFormationRequest(request);
        TrainingFormation formation = TrainingFormation.builder()
                .title(request.title().trim())
                .description(request.description())
                .category(request.category())
                .scheduledDate(request.scheduledDate())
                .scheduledTime(request.scheduledTime())
                .recurrenceType(request.recurrenceType() == null ? "NONE" : request.recurrenceType())
                .durationMinutes(request.durationMinutes())
                .videoMaxPlaybackRate(request.videoMaxPlaybackRate() == null ? 1.5 : Math.min(request.videoMaxPlaybackRate(), 1.5))
                .completionThresholdPercent(request.completionThresholdPercent() == null ? 95 : request.completionThresholdPercent())
                .status(request.status() == null ? "PLANNED" : request.status())
                .targetTeam(blankToNull(request.targetTeam()))
                .mandatory(request.mandatory() == null || request.mandatory())
                .createdByUserId(requesterId)
                .build();
        formation = formationRepository.save(formation);
        return toResponse(formation, requesterId);
    }

    @Transactional
    public TrainingFormationResponse updateFormation(Integer formationId, TrainingFormationRequest request, Long requesterId) {
        validateFormationRequest(request);
        TrainingFormation formation = getFormationOrThrow(formationId);
        formation.setTitle(request.title().trim());
        formation.setDescription(request.description());
        formation.setCategory(request.category());
        formation.setScheduledDate(request.scheduledDate());
        formation.setScheduledTime(request.scheduledTime());
        formation.setRecurrenceType(request.recurrenceType() == null ? "NONE" : request.recurrenceType());
        formation.setDurationMinutes(request.durationMinutes());
        if (request.videoMaxPlaybackRate() != null) {
            formation.setVideoMaxPlaybackRate(Math.min(request.videoMaxPlaybackRate(), 1.5));
        }
        if (request.completionThresholdPercent() != null) {
            formation.setCompletionThresholdPercent(request.completionThresholdPercent());
        }
        if (request.status() != null) {
            formation.setStatus(request.status());
        }
        formation.setTargetTeam(blankToNull(request.targetTeam()));
        if (request.mandatory() != null) {
            formation.setMandatory(request.mandatory());
        }
        return toResponse(formationRepository.save(formation), requesterId);
    }

    @Transactional
    public void deleteFormation(Integer formationId) {
        TrainingFormation formation = getFormationOrThrow(formationId);
        formationRepository.delete(formation);
    }

    // ===================== LECONS =====================

    @Transactional(readOnly = true)
    public List<TrainingLessonResponse> listLessons(Integer formationId, Long currentUserId) {
        getFormationOrThrow(formationId);
        List<TrainingLesson> lessons = lessonRepository.findByFormation_FormationIdOrderByOrderIndexAsc(formationId);
        return lessons.stream().map(l -> toLessonResponse(l, currentUserId, lessons)).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TrainingLessonResponse getLesson(Integer lessonId, Long currentUserId) {
        TrainingLesson lesson = getLessonOrThrow(lessonId);
        List<TrainingLesson> siblings = lessonRepository.findByFormation_FormationIdOrderByOrderIndexAsc(lesson.getFormation().getFormationId());
        return toLessonResponse(lesson, currentUserId, siblings);
    }

    @Transactional
    public TrainingLessonResponse createLesson(Integer formationId, TrainingLessonRequest request, Long requesterId) {
        TrainingFormation formation = getFormationOrThrow(formationId);
        if (request.title() == null || request.title().isBlank()) {
            throw ApiException.badRequest("Le titre de la lecon est obligatoire.");
        }
        int order = request.orderIndex() != null ? request.orderIndex()
                : (int) lessonRepository.countByFormation_FormationId(formationId);
        TrainingLesson lesson = TrainingLesson.builder()
                .formation(formation)
                .title(request.title().trim())
                .contentHtml(request.contentHtml())
                .videoUrl(request.videoUrl())
                .orderIndex(order)
                .estimatedMinutes(request.estimatedMinutes())
                .build();
        lesson = lessonRepository.save(lesson);
        return toLessonResponse(lesson, requesterId);
    }

    @Transactional
    public TrainingLessonResponse updateLesson(Integer lessonId, TrainingLessonRequest request, Long requesterId) {
        TrainingLesson lesson = getLessonOrThrow(lessonId);
        if (request.title() != null && !request.title().isBlank()) {
            lesson.setTitle(request.title().trim());
        }
        lesson.setContentHtml(request.contentHtml());
        lesson.setVideoUrl(request.videoUrl());
        if (request.orderIndex() != null) {
            lesson.setOrderIndex(request.orderIndex());
        }
        lesson.setEstimatedMinutes(request.estimatedMinutes());
        return toLessonResponse(lessonRepository.save(lesson), requesterId);
    }

    @Transactional
    public void deleteLesson(Integer lessonId) {
        TrainingLesson lesson = getLessonOrThrow(lessonId);
        lessonRepository.delete(lesson);
    }

    // ===================== PROGRESSION (suivi anti-triche) =====================

    @Transactional
    public TrainingProgressResponse updateProgress(Integer lessonId, Long userId, TrainingProgressUpdateRequest request) {
        TrainingLesson lesson = getLessonOrThrow(lessonId);
        TrainingProgress progress = progressRepository.findByLesson_LessonIdAndUserId(lessonId, userId)
                .orElseGet(() -> TrainingProgress.builder()
                        .lesson(lesson)
                        .userId(userId)
                        .startedAt(LocalDateTime.now())
                        .build());
        if (progress.getStartedAt() == null) {
            progress.setStartedAt(LocalDateTime.now());
        }

        double maxRate = lesson.getFormation().getVideoMaxPlaybackRate();
        boolean speedViolation = request.reportedPlaybackRate() != null && request.reportedPlaybackRate() > maxRate + 0.01;

        int incomingScroll = clampPercent(request.scrollPercent());
        // Le scroll ne peut jamais regresser : on garde le meilleur point atteint.
        progress.setScrollPercent(Math.max(progress.getScrollPercent(), incomingScroll));

        if (!speedViolation) {
            int incomingVideo = clampPercent(request.videoWatchedPercent());
            progress.setVideoWatchedPercent(Math.max(progress.getVideoWatchedPercent(), incomingVideo));
        } else {
            progress.setSpeedViolationCount(progress.getSpeedViolationCount() + 1);
        }

        progress.setLastActivityAt(LocalDateTime.now());

        int threshold = lesson.getFormation().getCompletionThresholdPercent();
        boolean hasVideo = lesson.getVideoUrl() != null && !lesson.getVideoUrl().isBlank();
        boolean scrollOk = progress.getScrollPercent() >= threshold;
        boolean videoOk = !hasVideo || progress.getVideoWatchedPercent() >= threshold;
        if (scrollOk && videoOk && !Boolean.TRUE.equals(progress.getCompleted())) {
            progress.setCompleted(true);
            progress.setCompletedAt(LocalDateTime.now());
        }

        progress = progressRepository.save(progress);
        return toProgressResponse(progress, hasVideo);
    }

    @Transactional(readOnly = true)
    public TrainingProgressResponse getProgress(Integer lessonId, Long userId) {
        TrainingLesson lesson = getLessonOrThrow(lessonId);
        boolean hasVideo = lesson.getVideoUrl() != null && !lesson.getVideoUrl().isBlank();
        return progressRepository.findByLesson_LessonIdAndUserId(lessonId, userId)
                .map(p -> toProgressResponse(p, hasVideo))
                .orElse(new TrainingProgressResponse(lessonId, 0, 0, 0, false, 0));
    }

    // ===================== STATISTIQUES QA (equipe / individu) =====================

    @Transactional(readOnly = true)
    public List<TeamTrainingStatsResponse> getTeamStats(Integer formationId) {
        TrainingFormation formation = getFormationOrThrow(formationId);
        List<AgentTrainingStatsResponse> agents = computeAgentStats(formation, null);
        Map<String, List<AgentTrainingStatsResponse>> byTeam = agents.stream()
                .collect(Collectors.groupingBy(a -> a.team() == null || a.team().isBlank() ? "Non affecte" : a.team()));

        List<TeamTrainingStatsResponse> result = new ArrayList<>();
        for (var entry : byTeam.entrySet()) {
            List<AgentTrainingStatsResponse> list = entry.getValue();
            double avg = list.stream().mapToDouble(AgentTrainingStatsResponse::overallPercent).average().orElse(0);
            long completedCount = list.stream().filter(AgentTrainingStatsResponse::fullyCompleted).count();
            long notStarted = list.stream().filter(a -> a.overallPercent() == 0).count();
            result.add(new TeamTrainingStatsResponse(entry.getKey(), list.size(), (int) completedCount, (int) notStarted,
                    Math.round(avg * 10) / 10.0));
        }
        result.sort(Comparator.comparing(TeamTrainingStatsResponse::team));
        return result;
    }

    @Transactional(readOnly = true)
    public List<AgentTrainingStatsResponse> getAgentStats(Integer formationId, String teamFilter) {
        TrainingFormation formation = getFormationOrThrow(formationId);
        return computeAgentStats(formation, teamFilter);
    }

    private List<AgentTrainingStatsResponse> computeAgentStats(TrainingFormation formation, String teamFilter) {
        List<TrainingLesson> lessons = lessonRepository.findByFormation_FormationIdOrderByOrderIndexAsc(formation.getFormationId());
        int totalLessons = lessons.size();
        List<TrainingProgress> allProgress = progressRepository.findAllByFormationId(formation.getFormationId());
        Map<Long, List<TrainingProgress>> byUser = allProgress.stream().collect(Collectors.groupingBy(TrainingProgress::getUserId));

        List<User> candidateUsers;
        if (formation.getTargetTeam() != null && !formation.getTargetTeam().isBlank()) {
            candidateUsers = userRepository.findAll().stream()
                    .filter(u -> formation.getTargetTeam().equalsIgnoreCase(u.getActivity()))
                    .collect(Collectors.toList());
        } else {
            Set<Long> userIds = new HashSet<>(byUser.keySet());
            candidateUsers = userRepository.findAllById(userIds);
        }

        if (teamFilter != null && !teamFilter.isBlank()) {
            candidateUsers = candidateUsers.stream()
                    .filter(u -> teamFilter.equalsIgnoreCase(u.getActivity()))
                    .collect(Collectors.toList());
        }

        List<AgentTrainingStatsResponse> result = new ArrayList<>();
        for (User u : candidateUsers) {
            List<TrainingProgress> progresses = byUser.getOrDefault(u.getId(), List.of());
            int completedLessons = (int) progresses.stream().filter(p -> Boolean.TRUE.equals(p.getCompleted())).count();
            double overall = totalLessons == 0 ? 0 : progresses.stream()
                    .mapToInt(p -> {
                        TrainingLesson lesson = p.getLesson();
                        boolean hasVideo = lesson.getVideoUrl() != null && !lesson.getVideoUrl().isBlank();
                        return hasVideo ? (p.getScrollPercent() + p.getVideoWatchedPercent()) / 2 : p.getScrollPercent();
                    })
                    .sum() / (double) totalLessons;
            boolean fullyCompleted = totalLessons > 0 && completedLessons == totalLessons;
            String status = fullyCompleted ? "Termine" : (overall > 0 ? "En cours" : "Non commence");
            result.add(new AgentTrainingStatsResponse(
                    u.getId(), u.getName(), u.getUsername(), u.getActivity(),
                    completedLessons, totalLessons, Math.round(overall * 10) / 10.0, fullyCompleted, status));
        }
        result.sort(Comparator.comparing(AgentTrainingStatsResponse::overallPercent));
        return result;
    }

    // ===================== HELPERS =====================

    private TrainingFormation getFormationOrThrow(Integer id) {
        return formationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Formation introuvable."));
    }

    private TrainingLesson getLessonOrThrow(Integer id) {
        return lessonRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Lecon introuvable."));
    }

    private void validateFormationRequest(TrainingFormationRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            throw ApiException.badRequest("Le titre de la formation est obligatoire.");
        }
        if (request.scheduledDate() == null) {
            throw ApiException.badRequest("La date de programmation (jour) est obligatoire.");
        }
        if (request.recurrenceType() != null
                && !Set.of("NONE", "WEEKLY", "MONTHLY").contains(request.recurrenceType())) {
            throw ApiException.badRequest("Type de recurrence invalide (NONE, WEEKLY ou MONTHLY).");
        }
    }

    private int clampPercent(Integer value) {
        if (value == null) return 0;
        return Math.max(0, Math.min(100, value));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private TrainingFormationResponse toResponse(TrainingFormation f, Long currentUserId) {
        int lessonCount = (int) lessonRepository.countByFormation_FormationId(f.getFormationId());
        Integer myPercent = null;
        if (currentUserId != null && lessonCount > 0) {
            List<TrainingProgress> mine = progressRepository.findByFormationIdAndUserId(f.getFormationId(), currentUserId);
            double sum = mine.stream().mapToInt(p -> {
                boolean hasVideo = p.getLesson().getVideoUrl() != null && !p.getLesson().getVideoUrl().isBlank();
                return hasVideo ? (p.getScrollPercent() + p.getVideoWatchedPercent()) / 2 : p.getScrollPercent();
            }).sum();
            myPercent = (int) Math.round(sum / lessonCount);
        }
        return new TrainingFormationResponse(
                f.getFormationId(), f.getTitle(), f.getDescription(), f.getCategory(),
                f.getScheduledDate(), f.getScheduledTime(), f.getRecurrenceType(), f.getDurationMinutes(),
                f.getVideoMaxPlaybackRate(), f.getCompletionThresholdPercent(), f.getStatus(),
                f.getTargetTeam(), f.getMandatory(), lessonCount, myPercent);
    }

    private TrainingLessonResponse toLessonResponse(TrainingLesson l, Long currentUserId) {
        List<TrainingLesson> siblings = lessonRepository.findByFormation_FormationIdOrderByOrderIndexAsc(l.getFormation().getFormationId());
        return toLessonResponse(l, currentUserId, siblings);
    }

    private TrainingLessonResponse toLessonResponse(TrainingLesson l, Long currentUserId, List<TrainingLesson> siblingsOrdered) {
        Integer scroll = 0, video = 0;
        Boolean completed = false;
        if (currentUserId != null) {
            var progress = progressRepository.findByLesson_LessonIdAndUserId(l.getLessonId(), currentUserId);
            if (progress.isPresent()) {
                scroll = progress.get().getScrollPercent();
                video = progress.get().getVideoWatchedPercent();
                completed = progress.get().getCompleted();
            }
        }
        Integer prevId = null, nextId = null;
        int idx = -1;
        for (int i = 0; i < siblingsOrdered.size(); i++) {
            if (siblingsOrdered.get(i).getLessonId().equals(l.getLessonId())) {
                idx = i;
                break;
            }
        }
        if (idx > 0) {
            prevId = siblingsOrdered.get(idx - 1).getLessonId();
        }
        if (idx >= 0 && idx < siblingsOrdered.size() - 1) {
            nextId = siblingsOrdered.get(idx + 1).getLessonId();
        }
        return new TrainingLessonResponse(
                l.getLessonId(), l.getFormation().getFormationId(), l.getTitle(), l.getContentHtml(),
                l.getVideoUrl(), l.getOrderIndex(), l.getEstimatedMinutes(),
                l.getFormation().getVideoMaxPlaybackRate(), scroll, video, completed,
                l.getFormation().getTitle(), prevId, nextId);
    }

    private TrainingProgressResponse toProgressResponse(TrainingProgress p, boolean hasVideo) {
        int overall = hasVideo ? (p.getScrollPercent() + p.getVideoWatchedPercent()) / 2 : p.getScrollPercent();
        return new TrainingProgressResponse(
                p.getLesson().getLessonId(), p.getScrollPercent(), p.getVideoWatchedPercent(),
                overall, p.getCompleted(), p.getSpeedViolationCount());
    }
}

package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.CourseService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Formation — consultation ouverte à tout compte connecté, création réservée à QA/admin. */
@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping
    public List<CourseResponse> listCourses(@AuthenticationPrincipal AuthenticatedUser requester) {
        return courseService.listCourses(requester);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse createCourse(@RequestBody CreateCourseRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return courseService.createCourse(request, requester);
    }

    @PutMapping("/{courseId}")
    public CourseResponse updateCourse(@PathVariable Integer courseId,
                                       @RequestBody CreateCourseRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return courseService.updateCourse(courseId, request);
    }

    @DeleteMapping("/{courseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCourse(@PathVariable Integer courseId,
                             @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        courseService.deleteCourse(courseId);
    }

    @PostMapping(value = "/{courseId}/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public CourseResponse uploadImage(@PathVariable Integer courseId,
                                      @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        // La QA contrôle la Formation de bout en bout (studio de création) : elle pose aussi la vignette.
        requireQaOrAdmin(requester);
        return courseService.updateCourseImage(courseId, file);
    }

    /** Vidéo du cours téléversée (mp4/m4v/mov/webm) — remplace le lien vidéo du cours. */
    @PostMapping(value = "/{courseId}/video", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public CourseResponse uploadVideo(@PathVariable Integer courseId,
                                      @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return courseService.updateCourseVideo(courseId, file);
    }

    /** Fichier joint au cours (PDF, Word...) — même équipe que le contenu, donc réservé QA. */
    @PostMapping(value = "/{courseId}/file", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public CourseResponse uploadFile(@PathVariable Integer courseId,
                                     @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                     @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return courseService.updateCourseFile(courseId, file);
    }

    /** Rubriques de formation avec image — grille façon Base de connaissances. */
    /** Contrôle QA : DRAFT → PUBLISHED → ARCHIVED (voir CourseService.updatePublication). */
    @PatchMapping("/{courseId}/publication")
    public CourseResponse updatePublication(@PathVariable Integer courseId, @RequestParam String status,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return courseService.updatePublication(courseId, status, requester);
    }

    @GetMapping("/categories")
    public List<com.ecobank.rccportal.dto.CourseCategoryResponse> listCategories() {
        return courseService.listCourseCategories();
    }

    @PostMapping(value = "/categories/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.ecobank.rccportal.dto.CourseCategoryResponse uploadCategoryImage(
            @RequestParam String title,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return courseService.uploadCategoryImage(title, file);
    }

    /** Progression de lecture vidéo — appelé régulièrement par le lecteur pendant la lecture,
     *  jamais bloquant pour l'agent (seekViolation est tracé, pas rejeté). */
    @PostMapping("/{courseId}/video-progress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateVideoProgress(@PathVariable Integer courseId,
                                    @RequestParam int watchedPercent,
                                    @RequestParam(defaultValue = "false") boolean seekViolation,
                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        courseService.updateVideoProgress(courseId, requester, watchedPercent, seekViolation);
    }

    @GetMapping("/{courseId}/questions")
    public List<CourseQuestionResponse> listQuestions(@PathVariable Integer courseId,
                                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        return courseService.listQuestions(courseId, isQaOrAdmin(requester));
    }

    @PostMapping("/{courseId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseQuestionResponse addQuestion(@PathVariable Integer courseId,
                                              @RequestBody CreateCourseQuestionRequest request,
                                              @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return courseService.addQuestion(courseId, request);
    }

    @PutMapping("/questions/{questionId}")
    public CourseQuestionResponse updateQuestion(@PathVariable Integer questionId,
                                                 @RequestBody CreateCourseQuestionRequest request,
                                                 @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        return courseService.updateQuestion(questionId, request);
    }

    @DeleteMapping("/questions/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQuestion(@PathVariable Integer questionId,
                               @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOnly(requester);
        courseService.deleteQuestion(questionId);
    }

    @GetMapping("/{courseId}/attempt")
    public CourseAttemptResponse getOrCreateAttempt(@PathVariable Integer courseId,
                                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        return courseService.getOrCreateAttempt(courseId, requester);
    }

    @PostMapping("/{courseId}/attempt/submit")
    public CourseAttemptResponse submitAttempt(@PathVariable Integer courseId,
                                               @RequestBody SubmitCourseAttemptRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        return courseService.submitAttempt(courseId, request, requester);
    }

    @GetMapping("/attempts/me")
    public List<CourseAttemptResponse> myAttempts(@AuthenticationPrincipal AuthenticatedUser requester) {
        return courseService.listMyAttempts(requester);
    }

    @GetMapping("/{courseId}/attempts")
    public List<CourseAttemptResponse> attemptsForCourse(@PathVariable Integer courseId,
                                                         @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return courseService.listAttemptsForCourse(courseId);
    }

    private boolean isQaOrAdmin(AuthenticatedUser requester) {
        return requester != null && ("admin".equalsIgnoreCase(requester.role())
                || (requester.service() != null && java.util.Set.of("quality assurance", "superviseur qa", "formateur").contains(requester.service().toLowerCase().replace('_', ' '))));
    }

    /** Lecture des résultats — accessible à QA et admin (supervision, pas de la création de contenu). */
    private void requireQaOrAdmin(AuthenticatedUser requester) {
        if (!isQaOrAdmin(requester)) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can view this.");
        }
    }

    /** ⚠️ Contenu (cours/questions) — réservé à la Quality Assurance (et son Superviseur), l'admin s'occupe des réglages/vignettes. */
    private void requireQaOnly(AuthenticatedUser requester) {
        boolean isQa = requester != null && requester.service() != null
                && java.util.Set.of("quality assurance", "superviseur qa", "formateur").contains(requester.service().toLowerCase().replace('_', ' '));
        if (!isQa) {
            throw ApiException.forbidden("Only Quality Assurance can manage course content.");
        }
    }
}
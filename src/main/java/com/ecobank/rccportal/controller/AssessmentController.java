package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AssessmentService;
import com.ecobank.rccportal.service.DocumentStorageService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Évaluations programmées : création depuis un support, relecture, publication (équipe + période), passage, résultats. */
@RestController
@RequestMapping("/api/assessments")
public class AssessmentController {

    private final AssessmentService service;
    private final DocumentStorageService documents;

    public AssessmentController(AssessmentService service, DocumentStorageService documents) {
        this.service = service;
        this.documents = documents;
    }

    @GetMapping("/teams")
    public Map<String, String> teams() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        AssessmentService.TEAMS.forEach(t -> out.put(t, AssessmentService.TEAM_LABELS.getOrDefault(t, t)));
        return out;
    }

    /** Brouillon rédigé automatiquement : fichier déposé, document/vidéo déjà joint à un cours (fileUrl) ou texte. */
    @PostMapping(value = "/draft", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public AssessmentService.Assessment draft(@RequestParam(value = "file", required = false) MultipartFile file,
                                              @RequestParam(required = false) String fileUrl,
                                              @RequestParam(required = false) String fileName,
                                              @RequestParam(required = false) String text,
                                              @RequestParam(required = false) String title,
                                              @RequestParam(required = false) Integer courseId,
                                              @RequestParam(defaultValue = "10") int count,
                                              @AuthenticationPrincipal AuthenticatedUser requester) {
        MultipartFile source = file;
        if ((source == null || source.isEmpty()) && fileUrl != null && !fileUrl.isBlank()) source = documents.open(fileUrl, fileName);
        String name = source != null && !source.isEmpty() ? source.getOriginalFilename() : fileName;
        return service.createDraft(requester, source, text, title, name, courseId, count);
    }

    @PutMapping("/{id}/questions/{questionId}")
    public AssessmentService.Assessment updateQuestion(@PathVariable int id, @PathVariable int questionId, @RequestBody Map<String, Object> body,
                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        @SuppressWarnings("unchecked")
        List<String> options = body.get("options") instanceof List<?> l ? (List<String>) l : null;
        Integer correct = body.get("correct") instanceof Number n ? n.intValue() : null;
        return service.updateQuestion(requester, id, questionId, (String) body.get("text"), options, correct);
    }

    @DeleteMapping("/{id}/questions/{questionId}")
    public AssessmentService.Assessment deleteQuestion(@PathVariable int id, @PathVariable int questionId,
                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.deleteQuestion(requester, id, questionId);
    }

    /** « Terminer » : équipe, période et note de réussite ; les agents et leurs Team Leaders sont prévenus. */
    @PostMapping("/{id}/publish")
    public AssessmentService.Assessment publish(@PathVariable int id, @RequestBody Map<String, Object> body,
                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        LocalDate from = parse(body.get("startsOn")), to = parse(body.get("endsOn"));
        Integer pass = body.get("passScore") instanceof Number n ? n.intValue() : null;
        return service.publish(requester, id, (String) body.get("title"), (String) body.get("teamCode"), from, to, pass);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable int id, @AuthenticationPrincipal AuthenticatedUser requester) {
        service.delete(requester, id);
    }

    @GetMapping
    public List<AssessmentService.Assessment> list(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.list(requester);
    }

    @GetMapping("/{id}")
    public AssessmentService.Assessment get(@PathVariable int id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.getForManager(requester, id);
    }

    @GetMapping("/{id}/results")
    public AssessmentService.Results results(@PathVariable int id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.results(requester, id);
    }

    /** Agent : mes évaluations (à faire, passées). */
    @GetMapping("/me")
    public List<AssessmentService.MyAssessment> mine(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.mine(requester);
    }

    @GetMapping("/me/results")
    public List<AssessmentService.MyAssessment> myResults(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.myResults(requester);
    }

    @GetMapping("/{id}/take")
    public AssessmentService.Assessment take(@PathVariable int id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.take(requester, id);
    }

    @PostMapping("/{id}/submit")
    public AssessmentService.SubmitResult submit(@PathVariable int id, @RequestBody Map<String, Integer> answers,
                                                 @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.submit(requester, id, answers);
    }

    /** Évaluation liée à un cours : l'agent y est conduit à la fin de sa formation. */
    @GetMapping("/for-course/{courseId}")
    public Map<String, Object> forCourse(@PathVariable int courseId) {
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("assessmentId", service.forCourse(courseId).orElse(null));
        return out;
    }

    private static LocalDate parse(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return LocalDate.parse(o.toString().substring(0, 10)); }
        catch (RuntimeException e) { throw ApiException.badRequest("Date invalide : " + o); }
    }
}

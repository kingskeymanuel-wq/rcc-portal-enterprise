package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.QuizQuestionService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Banque de questions enrichie (onglet "Questions") : recherche/filtre par
 * theme, difficulte, type, plus tirage aleatoire utilise par les jeux.
 */
@RestController
@RequestMapping("/api/quiz-questions")
public class QuizQuestionController {

    private final QuizQuestionService service;
    private final UserRepository userRepository;

    public QuizQuestionController(QuizQuestionService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<QuizQuestionResponse> list(@RequestParam(required = false) String category,
                                            @RequestParam(required = false) String difficulty,
                                            @RequestParam(required = false) String type,
                                            @RequestParam(required = false) String search,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.list(category, difficulty, type, search);
    }

    @GetMapping("/categories")
    public List<String> categories(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.listCategories();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuizQuestionResponse create(@RequestBody QuizQuestionRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.create(request, resolveUserId(requester));
    }

    @PutMapping("/{id}")
    public QuizQuestionResponse update(@PathVariable Integer id, @RequestBody QuizQuestionRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return service.update(id, request, resolveUserId(requester));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        service.delete(id);
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public void duplicate(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        service.duplicate(id);
    }

    /** Tirage aléatoire — utilisé par le lecteur de quiz et les jeux (pas de bonne réponse renvoyée). */
    @GetMapping("/draw")
    public List<QuizQuestionPlayResponse> draw(@RequestParam(required = false) String category,
                                                @RequestParam(required = false) String difficulty,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(defaultValue = "10") int count) {
        return service.drawRandom(category, difficulty, type, count);
    }

    @PostMapping("/{id}/answer")
    public QuizAnswerResult answer(@PathVariable Integer id, @RequestBody QuizAnswerSubmission submission) {
        return service.submitAnswer(id, submission);
    }

    /** Joker 50/50 — utilisé par le jeu Millions Ecobank. Ne révèle jamais la bonne réponse. */
    @GetMapping("/{id}/eliminate")
    public List<Integer> eliminate(@PathVariable Integer id) {
        return service.eliminateTwoWrongOptions(id);
    }

    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Seule la Quality Assurance ou un administrateur peut gérer la banque de questions.");
        }
    }

    private Long resolveUserId(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) return null;
        return userRepository.findFirstByUsernameIgnoreCase(requester.username()).map(User::getId).orElse(null);
    }
}

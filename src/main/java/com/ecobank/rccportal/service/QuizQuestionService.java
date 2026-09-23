package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.QuizQuestion;
import com.ecobank.rccportal.repository.QuizQuestionRepository;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Banque de questions centrale et enrichie (theme, difficulte, type, explication,
 * image, tags, points, temps limite) — alimente l'onglet "Questions" et sert de
 * reservoir commun aux jeux (voir GameService, a venir).
 */
@Service
public class QuizQuestionService {

    private static final Set<String> VALID_TYPES = Set.of("MCQ", "TRUE_FALSE", "MULTI_SELECT");
    private static final Set<String> VALID_DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD", "EXPERT");

    private final QuizQuestionRepository repository;
    private final ObjectMapper objectMapper;

    public QuizQuestionService(QuizQuestionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<QuizQuestionResponse> list(String category, String difficulty, String type, String search) {
        try {
            List<QuizQuestion> all = repository.findAll();
            return all.stream()
                    .filter(q -> category == null || category.isBlank() || category.equalsIgnoreCase(q.getCategory()))
                    .filter(q -> difficulty == null || difficulty.isBlank() || difficulty.equalsIgnoreCase(q.getDifficulty()))
                    .filter(q -> type == null || type.isBlank() || type.equalsIgnoreCase(q.getType()))
                    .filter(q -> search == null || search.isBlank()
                            || q.getQuestionText().toLowerCase().contains(search.toLowerCase())
                            || (q.getTags() != null && q.getTags().toLowerCase().contains(search.toLowerCase())))
                    .sorted(Comparator.comparing(QuizQuestion::getCreatedAt).reversed())
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public List<String> listCategories() {
        try {
            return repository.findAll().stream()
                    .map(QuizQuestion::getCategory)
                    .filter(c -> c != null && !c.isBlank())
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional
    public QuizQuestionResponse create(QuizQuestionRequest request, Long requesterId) {
        validate(request);
        QuizQuestion q = QuizQuestion.builder()
                .questionText(request.questionText().trim())
                .type(request.type())
                .difficulty(request.difficulty() == null ? "MEDIUM" : request.difficulty())
                .category(blankToNull(request.category()))
                .optionsJson(toJson(request.options()))
                .correctOptionIndex(request.correctOptionIndex())
                .correctIndexesJson(request.correctIndexes() == null ? null : toJson(request.correctIndexes()))
                .explanation(request.explanation())
                .imageUrl(blankToNull(request.imageUrl()))
                .videoUrl(blankToNull(request.videoUrl()))
                .tags(blankToNull(request.tags()))
                .points(request.points() == null ? 10 : request.points())
                .timeLimitSeconds(request.timeLimitSeconds())
                .active(request.active() == null || request.active())
                .createdByUserId(requesterId)
                .build();
        return toResponse(repository.save(q));
    }

    @Transactional
    public QuizQuestionResponse update(Integer id, QuizQuestionRequest request, Long requesterId) {
        validate(request);
        QuizQuestion q = getOrThrow(id);
        q.setQuestionText(request.questionText().trim());
        q.setType(request.type());
        q.setDifficulty(request.difficulty() == null ? "MEDIUM" : request.difficulty());
        q.setCategory(blankToNull(request.category()));
        q.setOptionsJson(toJson(request.options()));
        q.setCorrectOptionIndex(request.correctOptionIndex());
        q.setCorrectIndexesJson(request.correctIndexes() == null ? null : toJson(request.correctIndexes()));
        q.setExplanation(request.explanation());
        q.setImageUrl(blankToNull(request.imageUrl()));
        q.setVideoUrl(blankToNull(request.videoUrl()));
        q.setTags(blankToNull(request.tags()));
        if (request.points() != null) q.setPoints(request.points());
        q.setTimeLimitSeconds(request.timeLimitSeconds());
        if (request.active() != null) q.setActive(request.active());
        return toResponse(repository.save(q));
    }

    @Transactional
    public void delete(Integer id) {
        QuizQuestion q = getOrThrow(id);
        repository.delete(q);
    }

    @Transactional
    public void duplicate(Integer id) {
        QuizQuestion q = getOrThrow(id);
        QuizQuestion copy = QuizQuestion.builder()
                .questionText(q.getQuestionText() + " (copie)")
                .type(q.getType())
                .difficulty(q.getDifficulty())
                .category(q.getCategory())
                .optionsJson(q.getOptionsJson())
                .correctOptionIndex(q.getCorrectOptionIndex())
                .correctIndexesJson(q.getCorrectIndexesJson())
                .explanation(q.getExplanation())
                .imageUrl(q.getImageUrl())
                .videoUrl(q.getVideoUrl())
                .tags(q.getTags())
                .points(q.getPoints())
                .timeLimitSeconds(q.getTimeLimitSeconds())
                .active(q.getActive())
                .createdByUserId(q.getCreatedByUserId())
                .build();
        repository.save(copy);
    }

    /** Pioche aléatoirement N questions actives d'un theme/difficulte/type donnes — utilise par les jeux. */
    @Transactional(readOnly = true)
    public List<QuizQuestionPlayResponse> drawRandom(String category, String difficulty, String type, int count) {
        List<QuizQuestion> pool;
        if (category != null && !category.isBlank() && difficulty != null && !difficulty.isBlank()) {
            pool = repository.findByCategoryIgnoreCaseAndDifficultyIgnoreCaseAndActiveTrue(category, difficulty);
        } else if (category != null && !category.isBlank()) {
            pool = repository.findByCategoryIgnoreCaseAndActiveTrue(category);
        } else if (difficulty != null && !difficulty.isBlank()) {
            pool = repository.findByDifficultyIgnoreCaseAndActiveTrue(difficulty);
        } else {
            pool = repository.findByActiveTrueOrderByCreatedAtDesc();
        }
        if (type != null && !type.isBlank()) {
            pool = pool.stream().filter(q -> type.equalsIgnoreCase(q.getType())).collect(Collectors.toList());
        }
        Collections.shuffle(pool);
        return pool.stream().limit(Math.max(1, count)).map(this::toPlayResponse).collect(Collectors.toList());
    }

    @Transactional
    public QuizAnswerResult submitAnswer(Integer questionId, QuizAnswerSubmission submission) {
        QuizQuestion q = getOrThrow(questionId);
        boolean correct;
        if ("MULTI_SELECT".equals(q.getType())) {
            List<Integer> expected = fromJsonIntList(q.getCorrectIndexesJson());
            List<Integer> given = submission.selectedIndexes() == null ? List.of() : submission.selectedIndexes();
            correct = new HashSet<>(expected).equals(new HashSet<>(given));
        } else {
            correct = submission.selectedOptionIndex() != null && submission.selectedOptionIndex().equals(q.getCorrectOptionIndex());
        }
        q.setUsageCount(q.getUsageCount() + 1);
        if (correct) q.setCorrectAnswerCount(q.getCorrectAnswerCount() + 1);
        repository.save(q);
        return new QuizAnswerResult(q.getQuestionId(), correct, q.getCorrectOptionIndex(), q.getExplanation(),
                correct ? q.getPoints() : 0);
    }

    /** Joker 50/50 — élimine 2 options FAUSSES au hasard, sans jamais révéler laquelle est la bonne. */
    @Transactional(readOnly = true)
    public List<Integer> eliminateTwoWrongOptions(Integer questionId) {
        QuizQuestion q = getOrThrow(questionId);
        List<String> options = fromJsonStringList(q.getOptionsJson());
        List<Integer> wrongIndexes = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            if (!Integer.valueOf(i).equals(q.getCorrectOptionIndex())) wrongIndexes.add(i);
        }
        Collections.shuffle(wrongIndexes);
        return wrongIndexes.stream().limit(Math.min(2, wrongIndexes.size())).collect(Collectors.toList());
    }

    // ===================== HELPERS =====================

    private void validate(QuizQuestionRequest r) {
        if (r.questionText() == null || r.questionText().isBlank()) {
            throw ApiException.badRequest("Le texte de la question est obligatoire.");
        }
        String type = r.type() == null ? "MCQ" : r.type();
        if (!VALID_TYPES.contains(type)) {
            throw ApiException.badRequest("Type de question invalide (MCQ, TRUE_FALSE ou MULTI_SELECT).");
        }
        if (r.difficulty() != null && !VALID_DIFFICULTIES.contains(r.difficulty())) {
            throw ApiException.badRequest("Difficulté invalide (EASY, MEDIUM, HARD ou EXPERT).");
        }
        if (!"MULTI_SELECT".equals(type)) {
            if (r.options() == null || r.options().size() < 2) {
                throw ApiException.badRequest("Au moins 2 options de réponse sont requises.");
            }
            if (r.correctOptionIndex() == null || r.correctOptionIndex() < 0 || r.correctOptionIndex() >= r.options().size()) {
                throw ApiException.badRequest("La bonne réponse doit correspondre à une option existante.");
            }
        } else {
            if (r.options() == null || r.options().size() < 2) {
                throw ApiException.badRequest("Au moins 2 options de réponse sont requises.");
            }
            if (r.correctIndexes() == null || r.correctIndexes().isEmpty()) {
                throw ApiException.badRequest("Au moins une bonne réponse est requise pour une question à choix multiples.");
            }
        }
    }

    private QuizQuestion getOrThrow(Integer id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Question introuvable."));
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw ApiException.badRequest("Format invalide.");
        }
    }

    /**
     * Toutes les questions actives de la banque d'évaluation, résumées en "Q: ... / R: ..."
     * — utilisé par RalphSearchService pour injecter ces connaissances vérifiées dans le
     * contexte de RAF (voir buildQuizKnowledgeContext()). Chaque question ne peut être créée
     * QUE par QA/Admin (voir QuizQuestionController), donc son contenu est déjà validé —
     * jamais une réponse d'agent, toujours la bonne réponse de référence.
     */
    @Transactional(readOnly = true)
    public List<String> activeQuestionsKnowledgeSummary() {
        List<String> summaries = new java.util.ArrayList<>();
        for (QuizQuestion q : repository.findByActiveTrueOrderByCreatedAtDesc()) {
            List<String> options = fromJsonStringList(q.getOptionsJson());
            String correctAnswer;
            if ("MULTI_SELECT".equals(q.getType())) {
                List<Integer> indexes = fromJsonIntList(q.getCorrectIndexesJson());
                correctAnswer = indexes.stream()
                        .filter(i -> i != null && i >= 0 && i < options.size())
                        .map(options::get)
                        .collect(java.util.stream.Collectors.joining(", "));
            } else {
                Integer idx = q.getCorrectOptionIndex();
                correctAnswer = (idx != null && idx >= 0 && idx < options.size()) ? options.get(idx) : null;
            }
            if (correctAnswer == null || correctAnswer.isBlank()) continue;
            String line = "Q: " + q.getQuestionText() + "\nR: " + correctAnswer;
            if (q.getExplanation() != null && !q.getExplanation().isBlank()) {
                line += " — " + q.getExplanation();
            }
            summaries.add(line);
        }
        return summaries;
    }

    private List<String> fromJsonStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Integer> fromJsonIntList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private QuizQuestionResponse toResponse(QuizQuestion q) {
        return new QuizQuestionResponse(
                q.getQuestionId(), q.getQuestionText(), q.getType(), q.getDifficulty(), q.getCategory(),
                fromJsonStringList(q.getOptionsJson()), q.getCorrectOptionIndex(), fromJsonIntList(q.getCorrectIndexesJson()),
                q.getExplanation(), q.getImageUrl(), q.getVideoUrl(), q.getTags(), q.getPoints(), q.getTimeLimitSeconds(),
                q.getActive(), q.getUsageCount(), q.getCorrectAnswerCount(), q.getCreatedAt());
    }

    private QuizQuestionPlayResponse toPlayResponse(QuizQuestion q) {
        return new QuizQuestionPlayResponse(
                q.getQuestionId(), q.getQuestionText(), q.getType(), q.getDifficulty(), q.getCategory(),
                fromJsonStringList(q.getOptionsJson()), q.getImageUrl(), q.getVideoUrl(), q.getPoints(), q.getTimeLimitSeconds());
    }
}

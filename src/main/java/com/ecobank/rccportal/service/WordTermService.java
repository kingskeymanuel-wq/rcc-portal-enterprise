package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.WordTermRequest;
import com.ecobank.rccportal.dto.WordTermResponse;
import com.ecobank.rccportal.model.WordTerm;
import com.ecobank.rccportal.repository.WordTermRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class WordTermService {

    private final WordTermRepository repository;

    public WordTermService(WordTermRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<WordTermResponse> listAll() {
        return repository.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<WordTermResponse> drawRandom(int count) {
        List<WordTerm> pool = repository.findByActiveTrueOrderByTermAsc();
        Collections.shuffle(pool);
        return pool.stream().limit(Math.max(1, count)).map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public WordTermResponse create(WordTermRequest request) {
        validate(request);
        WordTerm t = WordTerm.builder()
                .term(request.term().trim().toUpperCase())
                .definition(request.definition().trim())
                .category(request.category())
                .active(request.active() == null || request.active())
                .build();
        return toResponse(repository.save(t));
    }

    @Transactional
    public WordTermResponse update(Integer id, WordTermRequest request) {
        validate(request);
        WordTerm t = repository.findById(id).orElseThrow(() -> ApiException.notFound("Terme introuvable."));
        t.setTerm(request.term().trim().toUpperCase());
        t.setDefinition(request.definition().trim());
        t.setCategory(request.category());
        if (request.active() != null) t.setActive(request.active());
        return toResponse(repository.save(t));
    }

    @Transactional
    public void delete(Integer id) {
        WordTerm t = repository.findById(id).orElseThrow(() -> ApiException.notFound("Terme introuvable."));
        repository.delete(t);
    }

    private void validate(WordTermRequest r) {
        if (r.term() == null || r.term().isBlank()) throw ApiException.badRequest("Le terme est obligatoire.");
        if (r.definition() == null || r.definition().isBlank()) throw ApiException.badRequest("La définition est obligatoire.");
    }

    private WordTermResponse toResponse(WordTerm t) {
        return new WordTermResponse(t.getTermId(), t.getTerm(), t.getDefinition(), t.getCategory(), t.getActive());
    }
}

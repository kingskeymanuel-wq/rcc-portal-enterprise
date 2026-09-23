package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.NewsArticleRequest;
import com.ecobank.rccportal.dto.NewsArticleResponse;
import com.ecobank.rccportal.model.NewsArticle;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.NewsArticleRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Actualités Ecobank — page d'accueil de tous les portails. Lecture ouverte à tous, édition réservée admin. */
@Service
public class NewsService {

    private final NewsArticleRepository newsArticleRepository;
    private final UserRepository userRepository;

    public NewsService(NewsArticleRepository newsArticleRepository, UserRepository userRepository) {
        this.newsArticleRepository = newsArticleRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<NewsArticleResponse> listAll() {
        return newsArticleRepository.findAllByOrderBySortOrderDescCreatedAtDesc().stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public NewsArticleResponse create(NewsArticleRequest request, String createdByUsername) {
        User author = userRepository.findFirstByUsernameIgnoreCase(createdByUsername).orElse(null);
        NewsArticle saved = newsArticleRepository.save(NewsArticle.builder()
                .title(request.title()).contentHtml(request.contentHtml()).imageUrl(request.imageUrl())
                .createdBy(author).sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
                .build());
        return toResponse(saved);
    }

    @Transactional
    public NewsArticleResponse update(Integer id, NewsArticleRequest request) {
        NewsArticle article = newsArticleRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Actualité introuvable."));
        if (request.title() != null) article.setTitle(request.title());
        if (request.contentHtml() != null) article.setContentHtml(request.contentHtml());
        if (request.imageUrl() != null) article.setImageUrl(request.imageUrl());
        if (request.sortOrder() != null) article.setSortOrder(request.sortOrder());
        newsArticleRepository.save(article);
        return toResponse(article);
    }

    @Transactional
    public void delete(Integer id) {
        if (!newsArticleRepository.existsById(id)) throw ApiException.notFound("Actualité introuvable.");
        newsArticleRepository.deleteById(id);
    }

    private NewsArticleResponse toResponse(NewsArticle a) {
        String authorName = null;
        try {
            authorName = a.getCreatedBy() != null ? a.getCreatedBy().getName() : null;
        } catch (jakarta.persistence.EntityNotFoundException ignored) {
            // auteur supprimé depuis — n'empêche pas l'affichage de l'actualité
        }
        return new NewsArticleResponse(a.getNewsId(), a.getTitle(), a.getContentHtml(), a.getImageUrl(),
                authorName, a.getSortOrder(), a.getCreatedAt(), a.getUpdatedAt());
    }
}

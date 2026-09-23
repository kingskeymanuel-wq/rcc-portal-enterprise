package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.NewsArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NewsArticleRepository extends JpaRepository<NewsArticle, Integer> {
    List<NewsArticle> findAllByOrderBySortOrderDescCreatedAtDesc();
}

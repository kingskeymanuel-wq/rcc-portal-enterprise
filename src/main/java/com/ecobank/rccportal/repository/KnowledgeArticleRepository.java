package com.ecobank.rccportal.repository;
import com.ecobank.rccportal.model.KnowledgeArticle;
import com.ecobank.rccportal.model.KnowledgeCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface KnowledgeArticleRepository extends JpaRepository<KnowledgeArticle, Integer> {

    List<KnowledgeArticle> findByCategoryAndCountry_CountryCodeOrderBySortOrderAsc(
            KnowledgeCategory category, String countryCode);

    /** Articles génériques (sans pays) pour une catégorie — ex. procédures valables partout. */
    List<KnowledgeArticle> findByCategoryAndCountryIsNullOrderBySortOrderAsc(KnowledgeCategory category);

    List<KnowledgeArticle> findByCategory_CategoryIdOrderBySortOrderAsc(Integer categoryId);

    List<KnowledgeArticle> findByTitleContainingIgnoreCaseOrTagsContainingIgnoreCase(String title, String tags);
}
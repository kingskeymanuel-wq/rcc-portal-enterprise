package com.ecobank.rccportal.repository;
import com.ecobank.rccportal.model.KnowledgeCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface KnowledgeCategoryRepository extends JpaRepository<KnowledgeCategory, Integer> {
    List<KnowledgeCategory> findAllByOrderBySortOrderAsc();
    java.util.Optional<KnowledgeCategory> findByCodeIgnoreCase(String code);
}
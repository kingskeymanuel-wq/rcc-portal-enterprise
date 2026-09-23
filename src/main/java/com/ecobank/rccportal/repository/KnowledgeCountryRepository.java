package com.ecobank.rccportal.repository;
import com.ecobank.rccportal.model.KnowledgeCountry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface KnowledgeCountryRepository extends JpaRepository<KnowledgeCountry, String> {
    List<KnowledgeCountry> findAllByOrderBySortOrderAsc();
}

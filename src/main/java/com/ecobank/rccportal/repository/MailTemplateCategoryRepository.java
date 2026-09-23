package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.MailTemplateCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MailTemplateCategoryRepository extends JpaRepository<MailTemplateCategory, Integer> {
    java.util.Optional<MailTemplateCategory> findByCodeIgnoreCase(String code);
}

package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.MailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MailTemplateRepository extends JpaRepository<MailTemplate, Integer> {
}

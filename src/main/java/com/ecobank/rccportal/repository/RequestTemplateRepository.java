package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RequestTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequestTemplateRepository extends JpaRepository<RequestTemplate, Integer> {
    List<RequestTemplate> findByActiveTrueOrderByNameAsc();
}

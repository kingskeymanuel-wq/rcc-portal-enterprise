package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.WordTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WordTermRepository extends JpaRepository<WordTerm, Integer> {
    List<WordTerm> findByActiveTrueOrderByTermAsc();
}

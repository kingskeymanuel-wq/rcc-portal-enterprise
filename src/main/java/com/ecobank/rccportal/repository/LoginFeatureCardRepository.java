package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.LoginFeatureCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginFeatureCardRepository extends JpaRepository<LoginFeatureCard, Integer> {
    List<LoginFeatureCard> findByActiveTrueOrderBySortOrderAsc();
    List<LoginFeatureCard> findAllByOrderBySortOrderAsc();
}

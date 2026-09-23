package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.SalesJourney;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SalesJourneyRepository extends JpaRepository<SalesJourney, Integer> {

    List<SalesJourney> findByActiveTrueOrderBySortOrderAsc();

    List<SalesJourney> findAllByOrderBySortOrderAsc();

    Optional<SalesJourney> findByJourneyKeyIgnoreCase(String key);
}

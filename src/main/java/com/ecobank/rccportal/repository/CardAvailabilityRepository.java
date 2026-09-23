package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.CardAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CardAvailabilityRepository extends JpaRepository<CardAvailability, Long> {

    List<CardAvailability> findByCardProductIdIn(Collection<Long> cardProductIds);

    Optional<CardAvailability> findByCardProductIdAndCityIgnoreCase(Long cardProductId, String city);

    void deleteByCardProductId(Long cardProductId);
}

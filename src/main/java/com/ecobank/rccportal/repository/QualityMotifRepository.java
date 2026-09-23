package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.QualityMotif;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QualityMotifRepository extends JpaRepository<QualityMotif, Integer> {
}

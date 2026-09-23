package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RccPole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RccPoleRepository extends JpaRepository<RccPole, Integer> {

    List<RccPole> findByIsActiveTrueOrderBySortOrderAscNameAsc();

    List<RccPole> findAllByOrderBySortOrderAscNameAsc();
}

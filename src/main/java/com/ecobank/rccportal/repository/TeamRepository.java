package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TeamRepository extends JpaRepository<Team, Integer> {
    Optional<Team> findByCode(String code);
}

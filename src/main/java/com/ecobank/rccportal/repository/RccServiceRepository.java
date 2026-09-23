package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RccService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RccServiceRepository
        extends JpaRepository<RccService, Long> {

    Optional<RccService> findByCodeIgnoreCase(String code);

    Optional<RccService> findByNameIgnoreCase(String name);
}
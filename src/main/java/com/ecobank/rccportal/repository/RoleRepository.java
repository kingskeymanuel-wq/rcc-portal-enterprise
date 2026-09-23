package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    /**
     * Recherche un rôle par son nom sans tenir compte
     * des majuscules/minuscules.
     *
     * Exemples :
     * AGENT
     * ADMIN
     * QA
     * IT
     */
    Optional<Role> findByNameIgnoreCase(String name);

    /**
     * Vérifie si un rôle existe déjà.
     */
    boolean existsByNameIgnoreCase(String name);
}
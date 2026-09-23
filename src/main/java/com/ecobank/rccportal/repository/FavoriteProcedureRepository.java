package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.FavoriteProcedure;
import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteProcedureRepository extends JpaRepository<FavoriteProcedure, Integer> {

    List<FavoriteProcedure> findByUserOrderByCreatedAtDesc(User user);

    Optional<FavoriteProcedure> findByUserAndProcedure(User user, Procedure procedure);

    boolean existsByUserAndProcedure(User user, Procedure procedure);

    void deleteByUserAndProcedure(User user, Procedure procedure);

    long countByProcedure(Procedure procedure);
}

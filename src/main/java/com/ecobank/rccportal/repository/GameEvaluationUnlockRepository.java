package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.GameEvaluationUnlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GameEvaluationUnlockRepository extends JpaRepository<GameEvaluationUnlock, Integer> {

    Optional<GameEvaluationUnlock> findFirstByGameKeyAndUserIdOrderByCreatedAtDesc(String gameKey, Long userId);
}

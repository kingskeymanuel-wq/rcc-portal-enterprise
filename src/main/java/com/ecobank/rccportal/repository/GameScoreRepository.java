package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.GameScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GameScoreRepository extends JpaRepository<GameScore, Integer> {
    List<GameScore> findByGameKeyOrderByScoreDesc(String gameKey);
    List<GameScore> findByGameKeyAndUserIdOrderByScoreDesc(String gameKey, Long userId);
}

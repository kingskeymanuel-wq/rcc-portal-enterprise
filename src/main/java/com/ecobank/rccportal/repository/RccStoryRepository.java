package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RccStory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RccStoryRepository extends JpaRepository<RccStory, Integer> {

    /** Stories actives : pas de date d'expiration, ou expiration dans le futur. */
    @Query("SELECT s FROM RccStory s WHERE s.expiresAt IS NULL OR s.expiresAt > :now ORDER BY s.publishedAt DESC")
    List<RccStory> findActive(LocalDateTime now);
}

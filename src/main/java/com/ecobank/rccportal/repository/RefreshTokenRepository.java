package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Integer> {

    Optional<RefreshToken> findByJti(UUID jti);

    @Modifying
    void deleteByJti(UUID jti);

    /** Purge des tokens expirés — utilisée par le scheduler de nettoyage. */
    @Modifying
    long deleteByExpiresAtBefore(LocalDateTime cutoff);

    /** Nombre de sessions actives (tokens non expirés) — utilisée par le dashboard admin. */
    long countByExpiresAtAfter(LocalDateTime cutoff);

    /** Sessions actives d'un utilisateur précis — pour "gérer les connexions" côté Administration. */
    java.util.List<RefreshToken> findByUser_IdAndExpiresAtAfterOrderByCreatedAtDesc(Long userId, LocalDateTime cutoff);

    /** Déconnexion forcée — révoque TOUTES les sessions d'un utilisateur, sur toutes ses machines. */
    @Modifying
    long deleteByUser_Id(Long userId);
}

package com.ecobank.rccportal.scheduler;

import com.ecobank.rccportal.repository.RefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Purge quotidienne des refresh tokens expirés (dbo.RefreshTokens). Sans
 * effet fonctionnel — isValid() les traite déjà comme invalides — mais évite
 * une croissance indéfinie de la table. Équivalent d'une tâche cron qu'on
 * ferait tourner à côté du service Node (non implémentée là-bas, ajoutée ici).
 */
@Slf4j
@Component
public class RefreshTokenCleanupJob {

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Scheduled(cron = "0 0 3 * * *") // tous les jours à 03h00
    @Transactional
    public void purgeExpiredTokens() {
        long deleted = refreshTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Purged {} expired refresh token(s)", deleted);
        }
    }
}

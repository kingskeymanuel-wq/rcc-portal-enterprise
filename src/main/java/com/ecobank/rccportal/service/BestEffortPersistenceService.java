package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.RefreshToken;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.RefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistances "best effort" appelées depuis AuthService, isolées dans leur
 * PROPRE transaction (REQUIRES_NEW) pour ne pas empoisonner la transaction
 * principale de completeLogin() en cas d'échec (ex: table absente).
 *
 * ⚠ Isoler la transaction ne suffit pas à éviter toute exception : si
 * l'opération échoue, Hibernate marque CETTE transaction REQUIRES_NEW comme
 * rollback-only, et le commit (géré par le proxy Spring, donc APRÈS le
 * retour de cette méthode) lève UnexpectedRollbackException. C'est
 * l'appelant (AuthService) qui doit catcher cette exception.
 */
@Slf4j
@Service
public class BestEffortPersistenceService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AttendanceService attendanceService;

    public BestEffortPersistenceService(
            RefreshTokenRepository refreshTokenRepository,
            AttendanceService attendanceService) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.attendanceService = attendanceService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveRefreshTokenBestEffort(RefreshToken entity) {
        try {
            refreshTokenRepository.save(entity);
        } catch (Exception e) {
            log.warn("Refresh token save failed (table missing?) for {}: {}",
                    entity.getUser().getUsername(), e.getMessage());
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clockInBestEffort(User user) {
        try {
            attendanceService.clockIn(user);
        } catch (Exception e) {
            log.warn("Attendance clock-in failed (table missing?) for {}: {}",
                    user.getUsername(), e.getMessage());
            throw e;
        }
    }
}
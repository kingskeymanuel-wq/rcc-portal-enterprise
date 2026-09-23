package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.JwtProperties;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Gestion des tentatives de connexion échouées.
 *
 * Cette classe utilise une transaction indépendante avec REQUIRES_NEW
 * afin que l'incrémentation du nombre de tentatives échouées soit
 * enregistrée même si la transaction d'authentification principale
 * est ensuite annulée.
 */
@Slf4j
@Service
public class LoginAttemptService {

    private final UserRepository userRepository;
    private final JwtProperties properties;

    public LoginAttemptService(
            UserRepository userRepository,
            JwtProperties properties) {

        this.userRepository = userRepository;
        this.properties = properties;
    }

    /**
     * Enregistre une tentative de connexion échouée.
     *
     * Lorsque le nombre maximal de tentatives est atteint,
     * le compte est temporairement verrouillé.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailedAttempt(User user) {

        if (user == null || user.getId() == null) {
            log.warn(
                    "Impossible d'enregistrer une tentative échouée : utilisateur invalide."
            );
            return;
        }

        int currentAttempts =
                user.getFailedAttempts() != null
                        ? user.getFailedAttempts()
                        : 0;

        int newAttempts = currentAttempts + 1;

        user.setFailedAttempts(newAttempts);

        /*
         * Vérifie si le nombre maximal de tentatives
         * autorisées a été atteint.
         */
        if (newAttempts >= properties.getMaxFailedAttempts()) {

            OffsetDateTime lockedUntil =
                    OffsetDateTime.now()
                            .plusMinutes(
                                    properties.getLockoutDurationMinutes()
                            );


            /*
             * Le compte est également marqué comme verrouillé
             * dans USERS.ACCOUNT_LOCKED.
             */
            user.setAccountLocked(true);

            /*
             * Réinitialisation du compteur après verrouillage.
             */
            user.setFailedAttempts(0);

            log.warn(
                    "Account locked after too many failed attempts "
                            + "(username={}, lockedUntil={})",
                    user.getUsername(),
                    lockedUntil
            );

        } else {

            log.warn(
                    "Failed login attempt "
                            + "(username={}, attempts={}/{})",
                    user.getUsername(),
                    newAttempts,
                    properties.getMaxFailedAttempts()
            );
        }

        userRepository.save(user);
    }

    /**
     * Réinitialise le compteur après une authentification réussie.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetFailedAttempts(User user) {

        if (user == null || user.getId() == null) {
            return;
        }

        user.setFailedAttempts(0);
        user.setAccountLocked(false);

        userRepository.save(user);

        log.debug(
                "Failed login attempts reset (username={})",
                user.getUsername()
        );
    }
}
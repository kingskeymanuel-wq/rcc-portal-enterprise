package com.ecobank.rccportal.service;

import com.ecobank.rccportal.security.JwtProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Gestion temporaire des challenges d'authentification RCC.
 *
 * Utilisé principalement pour :
 * - validation d'une étape d'authentification (login à deux étapes) ;
 * - éventuellement d'autres challenges temporaires.
 *
 * Aucun mot de passe Active Directory n'est stocké ici.
 *
 * Deux modes de lecture :
 *   - peek()    : valide un challenge SANS le détruire (plusieurs essais OTP
 *                 autorisés sur un même challenge) ;
 *   - consume() : valide ET détruit définitivement (usage unique — appelé
 *                 au succès ou à l'épuisement des essais).
 *
 * Architecture actuelle :
 *
 * Active Directory
 *       ↓
 * AuthService
 *       ↓
 * ChallengeService
 *       ↓
 * MFA (serveur Ecobank)
 *       ↓
 * JWT
 */
@Slf4j
@Service
public class ChallengeService {

    /**
     * Challenge interne stocké temporairement en mémoire.
     */
    private record Challenge(
            String username,
            String purpose,
            String code,
            Instant expiresAt
    ) {
    }

    /**
     * Stockage en mémoire.
     *
     * Pour une architecture multi-instance future,
     * ce stockage pourra être remplacé par Redis.
     */
    private final Map<String, Challenge> store =
            new ConcurrentHashMap<>();

    private final int ttlMinutes;

    public ChallengeService(
            JwtProperties properties) {

        this.ttlMinutes =
                properties.getTwoFactorCodeTtlMinutes();
    }

    /**
     * Résultat retourné après création d'un challenge.
     */
    public record Created(
            String challengeId,
            String username
    ) {
    }

    /**
     * Challenge consommé.
     */
    public record Consumed(
            String username,
            String purpose,
            String code
    ) {
    }

    /**
     * Crée un challenge sans code interne.
     *
     * Cas principal :
     * authentification à deux étapes (login).
     */
    public Created create(
            String username,
            String purpose) {

        return create(
                username,
                purpose,
                null
        );
    }

    /**
     * Crée un challenge.
     */
    public Created create(
            String username,
            String purpose,
            String code) {

        if (username == null ||
                username.isBlank()) {

            throw new IllegalArgumentException(
                    "username is required"
            );
        }

        if (purpose == null ||
                purpose.isBlank()) {

            throw new IllegalArgumentException(
                    "purpose is required"
            );
        }

        String normalizedUsername =
                username.trim().toLowerCase();

        String challengeId =
                UUID.randomUUID().toString();

        Instant expiresAt =
                Instant.now()
                        .plusSeconds(
                                ttlMinutes * 60L
                        );

        Challenge challenge =
                new Challenge(
                        normalizedUsername,
                        purpose,
                        code,
                        expiresAt
                );

        store.put(
                challengeId,
                challenge
        );

        log.debug(
                "Authentication challenge created " +
                        "(username={}, purpose={}, expiresAt={})",
                normalizedUsername,
                purpose,
                expiresAt
        );

        return new Created(
                challengeId,
                normalizedUsername
        );
    }

    /**
     * Consomme un challenge et retourne uniquement
     * le username.
     */
    public String consumeUsername(
            String challengeId,
            String purpose) {

        Consumed consumed =
                consume(
                        challengeId,
                        purpose
                );

        return consumed == null
                ? null
                : consumed.username();
    }

    /**
     * Alias temporaire pour l'ancien code.
     *
     * À supprimer lorsque toutes les anciennes références
     * à "username" auront disparu.
     */
    @Deprecated
    public String consumeusername(
            String challengeId,
            String purpose) {

        return consumeUsername(
                challengeId,
                purpose
        );
    }

    /**
     * Valide un challenge SANS le consommer (non destructif).
     *
     * Utilisé quand plusieurs essais sont autorisés sur un même challenge
     * (ex. plusieurs essais OTP) : on revalide à chaque essai, mais on ne
     * détruit le challenge qu'au succès ou à l'épuisement des essais, via
     * consume(). Un challenge expiré est tout de même retiré du store.
     *
     * @return le Consumed correspondant, ou null si absent / expiré /
     *         purpose non accepté.
     */
    public Consumed peek(
            String challengeId,
            String... acceptedPurposes) {

        Challenge challenge =
                lookupValid(challengeId, acceptedPurposes);

        if (challenge == null) {
            return null;
        }

        return new Consumed(
                challenge.username(),
                challenge.purpose(),
                challenge.code()
        );
    }

    /**
     * Consomme définitivement un challenge.
     *
     * Un challenge consommé ne peut pas être réutilisé.
     */
    public Consumed consume(
            String challengeId,
            String... acceptedPurposes) {

        Challenge challenge =
                lookupValid(challengeId, acceptedPurposes);

        if (challenge == null) {
            return null;
        }

        /*
         * Suppression avant retour :
         * challenge utilisable une seule fois.
         */
        store.remove(challengeId);

        return new Consumed(
                challenge.username(),
                challenge.purpose(),
                challenge.code()
        );
    }

    /**
     * Validation commune (existence, expiration, purpose) SANS consommation.
     *
     * Retourne le Challenge valide, ou null si absent / expiré / purpose non
     * accepté. Un challenge expiré est retiré du store au passage.
     */
    private Challenge lookupValid(
            String challengeId,
            String... acceptedPurposes) {

        if (challengeId == null ||
                challengeId.isBlank()) {

            return null;
        }

        Challenge challenge =
                store.get(challengeId);

        if (challenge == null) {

            return null;
        }

        /*
         * Vérification expiration.
         */
        if (challenge.expiresAt()
                .isBefore(
                        Instant.now()
                )) {

            store.remove(
                    challengeId
            );

            log.debug(
                    "Expired authentication challenge removed."
            );

            return null;
        }

        /*
         * Vérification du purpose.
         */
        boolean matches = false;

        if (acceptedPurposes != null) {

            for (String purpose :
                    acceptedPurposes) {

                if (purpose != null &&
                        challenge.purpose()
                                .equals(purpose)) {

                    matches = true;
                    break;
                }
            }
        }

        if (!matches) {

            log.warn(
                    "Authentication challenge rejected: " +
                            "unexpected purpose."
            );

            return null;
        }

        return challenge;
    }

    /**
     * Supprime périodiquement les challenges expirés.
     *
     * Empêche la Map de grossir indéfiniment.
     */
    @Scheduled(
            fixedRate = 5,
            timeUnit = TimeUnit.MINUTES
    )
    public void purgeExpired() {

        Instant now =
                Instant.now();

        int before =
                store.size();

        store.entrySet()
                .removeIf(
                        entry ->
                                entry.getValue()
                                        .expiresAt()
                                        .isBefore(now)
                );

        int purged =
                before - store.size();

        if (purged > 0) {

            log.info(
                    "Purged {} expired authentication challenge(s)",
                    purged
            );
        }
    }
}
package com.ecobank.rccportal.dto;

/**
 * challengeId/maskedEmail sont null et session non-null quand twoFactorRequired est false —
 * cas des comptes EXCELLIAM (prestataire externe, absent de l'AD Ecobank, donc pas de code MFA
 * possible pour eux) : voir AuthService.initiateLogin, qui authentifie et ouvre directement la
 * session dès cette étape 1, sans jamais créer de challenge OTP.
 *
 * requiresPasswordSetup=true : compte EXCELLIAM reconnu mais sans mot de passe encore défini
 * (première connexion, ou après réinitialisation) — challengeId/maskedEmail/session sont tous
 * null dans ce cas, le frontend doit proposer l'écran de création de mot de passe
 * (POST /api/auth/excelliam/set-password) plutôt que d'afficher une erreur.
 */
public record LoginChallengeResponse(

        String challengeId,

        String maskedEmail,

        boolean twoFactorRequired,

        /** Non-null uniquement si twoFactorRequired = false — session déjà ouverte, le frontend
         *  n'affiche pas l'écran de saisie du code et passe directement au tableau de bord. */
        com.ecobank.rccportal.service.AuthService.SessionTokens session,

        boolean requiresPasswordSetup

) {
}
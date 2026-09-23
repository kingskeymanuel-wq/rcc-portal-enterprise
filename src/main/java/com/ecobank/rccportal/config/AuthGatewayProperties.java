package com.ecobank.rccportal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration de la gateway d'authentification SAGED centralisée —
 * remplace les appels séparés AD (AdAuthClient/rechercherUserAD) et MFA
 * (MfaService/ecimfa) par un seul appel signé (HMAC-SHA512) + chiffré
 * (AES-256-GCM) vers {@code login-url}, avec {@code {username, password, otp}}
 * en un seul payload. Voir AuthGatewayClient.
 *
 * <p>Alignée sur Closed Loop Service (com.ecobank.closedloopservice.config
 * .AuthProperties) — même gateway, même contrat cryptographique. Les valeurs
 * de {@code hmac-secret}, {@code aes-password} et {@code login-url} DOIVENT
 * être confirmées avec l'IT Ecobank (identiques à celles déjà utilisées par
 * Closed Loop Service / Intérim / Salaire / Virement, à récupérer auprès
 * d'eux plutôt qu'à réinventer) et renseignées en variables d'environnement
 * — jamais commitées en clair.</p>
 *
 * À ajouter dans application.yml :
 * <pre>
 * rcc:
 *   auth:
 *     gateway:
 *       login-url: "${RCC_AUTH_GATEWAY_LOGIN_URL:}"
 *       hmac-secret: "${RCC_AUTH_GATEWAY_HMAC_SECRET:}"
 *       aes-password: "${RCC_AUTH_GATEWAY_AES_PASSWORD:}"
 *       timeout-ms: 15000
 *       insecure-ssl: true
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rcc.auth.gateway")
public class AuthGatewayProperties {

    /** URL complète de l'endpoint de login de la gateway SAGED (auth.login-url côté Closed Loop Service). */
    private String loginUrl = "";

    /** URL de déconnexion gateway — révélée par la collection Postman "Saged" ("Auth Gateway Logout"). */
    private String logoutUrl = "";

    /** URL de rafraîchissement de jeton — révélée par la collection Postman "Saged" ("Refresh"),
     *  appel NON chiffré/signé (JSON en clair {refresh_token, username}). */
    private String refreshUrl = "";

    /** Clé secrète partagée pour la signature HMAC-SHA512 du payload — jamais de valeur par défaut réelle. */
    private String hmacSecret = "";

    /** Mot de passe de dérivation de clé AES-256-GCM (PBKDF2) — jamais de valeur par défaut réelle. */
    private String aesPassword = "";

    /** Timeout de l'appel en millisecondes. */
    private int timeoutMs = 15000;

    /**
     * Désactive la validation du certificat SSL — nécessaire tant que la CA
     * interne Ecobank n'est pas importée dans le trust store du JDK, même
     * justification que AdAuthProperties/MfaProperties.
     */
    private boolean insecureSsl = true;
}

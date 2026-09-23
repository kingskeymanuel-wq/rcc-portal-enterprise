package com.ecobank.rccportal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration du serveur MFA Ecobank CI (ecimfa) — vérification OTP.
 *
 * À ajouter dans application.yml :
 *
 * rcc:
 *   auth:
 *     mfa:
 *       base-url: "https://10.16.1.16"
 *       activation-path: "/ecimfa/authentification/activation"
 *       source: "RccPortal"          # ⚠ à confirmer avec l'IT Ecobank
 *       timeout-ms: 30000
 *       insecure-ssl: true            # certificat auto-signé / CA interne
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rcc.auth.mfa")
public class MfaProperties {

    /** Host/base URL du serveur MFA (ex: https://10.16.1.16) */
    private String baseUrl = "https://10.16.1.16";

    /** Chemin du endpoint d'activation/vérification OTP */
    private String activationPath = "/ecimfa/authentification/activation";

    /** Identifiant de l'application source, transmis dans <source> */
    private String source = "RccPortal";

    /** Timeout de l'appel en millisecondes */
    private int timeoutMs = 30000;

    /**
     * Désactive la validation du certificat SSL — nécessaire tant que la CA
     * interne Ecobank n'est pas importée dans le trust store du JDK.
     * À passer à false une fois keytool -importcert effectué.
     */
    private boolean insecureSsl = true;
}




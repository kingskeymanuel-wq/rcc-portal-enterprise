package com.ecobank.rccportal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration Copilot Studio, liée depuis application.yml (préfixe "copilot") — jamais de
 * secret en dur, toujours via variable d'environnement (COPILOT_DIRECTLINE_SECRET) relayée
 * par application.yml. "enabled" est un interrupteur explicite indépendant de la présence du
 * secret : permet de désactiver RAF-Copilot Studio (retour au repli mots-clés) sans avoir à
 * retirer le secret, utile en cas de rollback ou de maintenance côté Copilot Studio.
 *
 * À ajouter dans application.yml :
 *
 * copilot:
 *   enabled: ${COPILOT_ENABLED:false}
 *   directline:
 *     secret: ${COPILOT_DIRECTLINE_SECRET:}
 *     endpoint: ${COPILOT_DIRECTLINE_ENDPOINT:https://directline.botframework.com}
 *     timeout-seconds: ${COPILOT_DIRECTLINE_TIMEOUT_SECONDS:60}
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "copilot")
public class CopilotProperties {

    private boolean enabled = false;
    private Directline directline = new Directline();

    @Data
    public static class Directline {
        private String secret = "";
        private String endpoint = "https://directline.botframework.com";
        private int timeoutSeconds = 60;
    }
}


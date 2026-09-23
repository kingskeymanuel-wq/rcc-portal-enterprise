package com.ecobank.rccportal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration du repli "recherche web" de RAF, liée depuis application.yml
 * (préfixe "websearch") — jamais de clé en dur, toujours via variable
 * d'environnement relayée par application.yml.
 *
 * "enabled" est un interrupteur explicite indépendant de la présence de la
 * clé : permet de couper le repli web (RAF reste 100% interne) sans avoir
 * à retirer la clé, utile en cas de restriction réseau ou de politique de
 * confidentialité plus stricte sur une filiale donnée.
 *
 * Utilise Bing Web Search API (Azure Cognitive Services) — cohérent avec le
 * reste de l'architecture IA du portail, déjà 100% Azure (Speech, OpenAI).
 *
 * À ajouter dans application.yml :
 *
 * websearch:
 *   enabled: ${WEBSEARCH_ENABLED:false}
 *   bing:
 *     endpoint: ${WEBSEARCH_BING_ENDPOINT:https://api.bing.microsoft.com/v7.0/search}
 *     key: ${WEBSEARCH_BING_KEY:}
 *     market: ${WEBSEARCH_BING_MARKET:fr-FR}
 *     result-count: ${WEBSEARCH_BING_RESULT_COUNT:5}
 *     timeout-seconds: ${WEBSEARCH_BING_TIMEOUT_SECONDS:15}
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "websearch")
public class WebSearchProperties {

    private boolean enabled = false;
    private Bing bing = new Bing();

    @Data
    public static class Bing {
        private String endpoint = "https://api.bing.microsoft.com/v7.0/search";
        private String key = "";
        private String market = "fr-FR";
        private int resultCount = 5;
        private int timeoutSeconds = 15;
    }
}

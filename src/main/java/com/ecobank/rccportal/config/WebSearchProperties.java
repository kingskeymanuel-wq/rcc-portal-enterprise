package com.ecobank.rccportal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration de la recherche web (repli de la barre de recherche et de RAF), liée depuis
 * application.yml (préfixe "websearch") — jamais de clé en dur.
 *
 * <p>"enabled" est un interrupteur global indépendant des clés : permet de couper toute
 * recherche web sans retirer les clés (politique de confidentialité d'une filiale...).</p>
 *
 * <p>Plusieurs moteurs, essayés dans l'ordre de {@code provider-order} jusqu'au premier qui
 * renvoie des résultats. Bing Web Search API a été retirée par Microsoft le 11/08/2025 :
 * elle reste listée pour les installations qui auraient encore une ressource active, mais
 * n'est plus essayée par défaut.</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "websearch")
public class WebSearchProperties {

    private boolean enabled = false;
    /** Ordre d'essai (identifiants : searxng, brave, tavily, google, bing, wikipedia). */
    private String providerOrder = "searxng,brave,tavily,google,wikipedia";
    /** Langue préférée des résultats (code ISO 639-1). */
    private String language = "fr";
    private int resultCount = 5;
    private int timeoutSeconds = 10;
    /** Durée de cache des résultats (limite la consommation de quota), 0 = pas de cache. */
    private int cacheMinutes = 15;

    private Searxng searxng = new Searxng();
    private Brave brave = new Brave();
    private Tavily tavily = new Tavily();
    private Google google = new Google();
    private Bing bing = new Bing();
    private Wikipedia wikipedia = new Wikipedia();

    /** SearXNG — méta-moteur open source auto-hébergé par l'IT (aucune clé, aucune donnée chez un tiers). */
    @Data
    public static class Searxng {
        private String url = "";
    }

    /** Brave Search API — https://api.search.brave.com (offre gratuite disponible). */
    @Data
    public static class Brave {
        private String endpoint = "https://api.search.brave.com/res/v1/web/search";
        private String key = "";
    }

    /** Tavily Search API — https://tavily.com (offre gratuite disponible). */
    @Data
    public static class Tavily {
        private String endpoint = "https://api.tavily.com/search";
        private String key = "";
    }

    /** Google Programmable Search Engine (Custom Search JSON API). */
    @Data
    public static class Google {
        private String endpoint = "https://www.googleapis.com/customsearch/v1";
        private String key = "";
        /** Identifiant du moteur (paramètre "cx"). */
        private String cx = "";
    }

    /** Bing Web Search API — RETIRÉE par Microsoft (août 2025), conservée pour compatibilité. */
    @Data
    public static class Bing {
        private String endpoint = "https://api.bing.microsoft.com/v7.0/search";
        private String key = "";
        private String market = "fr-FR";
        private int resultCount = 5;
        private int timeoutSeconds = 15;
    }

    /** Wikipédia (API MediaWiki publique) — gratuit, sans clé : filet de sécurité encyclopédique. */
    @Data
    public static class Wikipedia {
        private boolean enabled = true;
    }
}

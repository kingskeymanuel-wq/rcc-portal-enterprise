package com.ecobank.rccportal.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mode « serveur sans Internet » (RCC_OFFLINE=true) : aucun service hébergé sur Internet n'est appelé
 * (Anthropic, Azure, Microsoft Graph, Copilot, recherche web, traducteurs en ligne, OpenStreetMap). Les
 * fonctions basculent sur leurs équivalents installés sur le réseau interne (IA locale, Whisper, LibreTranslate,
 * PaddleOCR, serveur de tuiles) ou sur leur repli local — sans attente réseau. Voir docs/ANALYSE_HORS_LIGNE.md.
 */
@Component
public class OfflineMode {

    /** Réglage enregistré (dbo.SiteSettings) quand l'administrateur bascule le mode depuis l'Administration. */
    public static final String SETTING_KEY = "portal.offline";

    private volatile boolean enabled;
    private SiteSettingService settings;
    private volatile boolean loaded;

    public OfflineMode(@Value("${rcc.offline:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettings(@org.springframework.context.annotation.Lazy SiteSettingService settings) {
        this.settings = settings;
    }

    /** Valeur de démarrage (RCC_OFFLINE), remplacée par le choix de l'administrateur s'il en a fait un. */
    public boolean isEnabled() {
        if (!loaded && settings != null) {
            loaded = true;
            try {
                String v = settings.get(SETTING_KEY);
                if (v != null) enabled = Boolean.parseBoolean(v);
            } catch (RuntimeException e) {
                loaded = false; // base momentanément indisponible : nouvel essai au prochain appel
            }
        }
        return enabled;
    }

    /** Bascule immédiate (sans redémarrage) et conservée après redémarrage. */
    public void setEnabled(boolean on) {
        if (settings != null) settings.set(SETTING_KEY, Boolean.toString(on));
        enabled = on;
        loaded = true;
    }

    /** Hôte sur Internet (pas une adresse locale ou privée) : à ne pas appeler en mode hors ligne. */
    public static boolean isInternetUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String host;
        try {
            host = java.net.URI.create(url.trim().replace("{s}", "a").replace("{z}", "0").replace("{x}", "0").replace("{y}", "0")).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.equals("localhost") || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || !h.contains(".")) return false;
        if (h.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")) return false;
        return !(h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".intra") || h.endsWith(".internal") || h.endsWith(".ecobank.group"));
    }
}

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

    private final boolean enabled;

    public OfflineMode(@Value("${rcc.offline:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
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

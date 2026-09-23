package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.SiteSetting;
import com.ecobank.rccportal.repository.SiteSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Réglages d'apparence du site, pilotables depuis QA/Admin sans toucher au code — photo de
 * connexion (image + opacité) pour l'instant, extensible à d'autres emplacements plus tard
 * (même mécanisme clé/valeur pour tout nouvel emplacement à ajouter).
 */
@Service
public class SiteSettingService {

    public static final String LOGIN_HERO_IMAGE_URL = "login.hero.imageUrl";
    public static final String LOGIN_HERO_IMAGE_URLS = "login.hero.imageUrls"; // JSON array — carousel, complète LOGIN_HERO_IMAGE_URL
    public static final String MONRCC_BANNER_IMAGE_URL = "monrcc.banner.imageUrl";
    public static final String LOGIN_HERO_OPACITY = "login.hero.opacity";
    public static final String LOGIN_FEATURES_FONT_FAMILY = "login.features.fontFamily";
    public static final String LOGIN_FEATURES_LINE_HEIGHT = "login.features.lineHeight";

    /** Réglages lisibles sans authentification (page de connexion) — jamais de contenu sensible ici. */
    private static final java.util.Set<String> PUBLIC_KEYS = java.util.Set.of(
            LOGIN_HERO_IMAGE_URL, LOGIN_HERO_IMAGE_URLS, LOGIN_HERO_OPACITY,
            LOGIN_FEATURES_FONT_FAMILY, LOGIN_FEATURES_LINE_HEIGHT);

    private final SiteSettingRepository siteSettingRepository;
    private final ImageStorageService imageStorageService;

    public SiteSettingService(SiteSettingRepository siteSettingRepository, ImageStorageService imageStorageService) {
        this.siteSettingRepository = siteSettingRepository;
        this.imageStorageService = imageStorageService;
    }

    @Transactional(readOnly = true)
    public Map<String, String> publicSettings() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : PUBLIC_KEYS) {
            siteSettingRepository.findById(key).ifPresent(s -> result.put(key, s.getSettingValue()));
        }
        return result;
    }

    /** Réglages lisibles par tout utilisateur connecté (bannière MON RCC...) — pas de contenu sensible, juste pas public avant login. */
    @Transactional(readOnly = true)
    public String get(String key) {
        return siteSettingRepository.findById(key).map(SiteSetting::getSettingValue).orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<String, String> allSettings() {
        Map<String, String> result = new LinkedHashMap<>();
        siteSettingRepository.findAll().forEach(s -> result.put(s.getSettingKey(), s.getSettingValue()));
        return result;
    }

    @Transactional
    public void set(String key, String value) {
        SiteSetting setting = siteSettingRepository.findById(key)
                .orElseGet(() -> SiteSetting.builder().settingKey(key).build());
        setting.setSettingValue(value);
        siteSettingRepository.save(setting);
    }

    /** Upload une image et l'enregistre directement comme valeur du réglage donné — évite un aller-retour côté frontend. */
    @Transactional
    public String setImage(String key, MultipartFile file) {
        String url = imageStorageService.store(file);
        set(key, url);
        return url;
    }

    // ===================== Carousel de photos de connexion (plusieurs images) =====================

    @Transactional(readOnly = true)
    public java.util.List<String> getHeroImageList() {
        String raw = get(LOGIN_HERO_IMAGE_URLS);
        if (raw == null || raw.isBlank()) return new java.util.ArrayList<>();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    private void saveHeroImageList(java.util.List<String> urls) {
        try {
            set(LOGIN_HERO_IMAGE_URLS, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(urls));
        } catch (Exception e) {
            throw new RuntimeException("Impossible d'enregistrer la liste d'images.", e);
        }
    }

    /** Upload une image et l'ajoute à la fin du carousel de la page de connexion. */
    @Transactional
    public java.util.List<String> addHeroImage(MultipartFile file) {
        String url = imageStorageService.store(file);
        java.util.List<String> urls = getHeroImageList();
        urls.add(url);
        saveHeroImageList(urls);
        return urls;
    }

    @Transactional
    public java.util.List<String> removeHeroImage(String url) {
        java.util.List<String> urls = getHeroImageList();
        urls.remove(url);
        saveHeroImageList(urls);
        return urls;
    }
}

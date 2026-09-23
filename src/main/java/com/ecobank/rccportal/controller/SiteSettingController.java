package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.SiteSettingService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** Apparence du site (photo de connexion...), pilotable depuis QA/Admin — jamais de code à modifier pour un simple changement d'image. */
@RestController
@RequestMapping("/api/site-settings")
public class SiteSettingController {

    private final SiteSettingService siteSettingService;

    public SiteSettingController(SiteSettingService siteSettingService) {
        this.siteSettingService = siteSettingService;
    }

    /** Public — nécessaire pour la page de connexion, avant authentification. Jamais de contenu sensible ici. */
    @GetMapping("/public")
    public Map<String, String> publicSettings() {
        return siteSettingService.publicSettings();
    }

    /** Lisible par tout utilisateur connecté (bannière MON RCC) — pas d'exigence QA/Admin ici, juste authentifié. */
    @GetMapping("/monrcc-banner")
    public Map<String, String> monRccBanner() {
        return Map.of("value", java.util.Optional.ofNullable(
                siteSettingService.get(SiteSettingService.MONRCC_BANNER_IMAGE_URL)).orElse(""));
    }

    /** Même principe, généralisé à n'importe quelle clé — lisible par tout utilisateur connecté (bannières, jamais de contenu sensible). */
    @GetMapping("/public-value/{key}")
    public Map<String, String> publicValue(@PathVariable String key) {
        return Map.of("value", java.util.Optional.ofNullable(siteSettingService.get(key)).orElse(""));
    }

    @GetMapping
    public Map<String, String> all(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return siteSettingService.allSettings();
    }

    @PostMapping("/{key}")
    public void set(@PathVariable String key, @RequestBody Map<String, String> body,
                     @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        siteSettingService.set(key, body.get("value"));
    }

    /** Public — nécessaire pour que la page de connexion affiche le carousel avant authentification. */
    @GetMapping("/login-hero-images")
    public java.util.List<String> heroImages() {
        return siteSettingService.getHeroImageList();
    }

    @PostMapping(value = "/login-hero-images", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public java.util.List<String> addHeroImage(@RequestParam("file") MultipartFile file,
                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return siteSettingService.addHeroImage(file);
    }

    @PostMapping("/login-hero-images/remove")
    public java.util.List<String> removeHeroImage(@RequestBody Map<String, String> body,
                                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return siteSettingService.removeHeroImage(body.get("url"));
    }

    @PostMapping(value = "/{key}/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> setImage(@PathVariable String key, @RequestParam("file") MultipartFile file,
                                         @AuthenticationPrincipal AuthenticatedUser requester) {
        requireQaOrAdmin(requester);
        return Map.of("value", siteSettingService.setImage(key, file));
    }

    private void requireQaOrAdmin(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can change site appearance settings.");
        }
    }
}

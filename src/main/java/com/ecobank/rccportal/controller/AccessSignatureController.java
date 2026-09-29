package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.TabPermissionService;
import com.ecobank.rccportal.service.UserFeaturePermissionService;
import com.ecobank.rccportal.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Empreinte de l'accès réel de l'utilisateur connecté : profil, service, équipe et portail, onglets interdits,
 * fonctionnalités accordées ou retirées. Chaque page du portail la relit régulièrement (session.js) : dès qu'un
 * changement fait dans Administration la modifie, la page se met à jour d'elle-même — sans reconnexion.
 */
@RestController
public class AccessSignatureController {

    private final UserService userService;
    private final TabPermissionService tabPermissions;
    private final UserFeaturePermissionService featurePermissions;

    public AccessSignatureController(UserService userService, TabPermissionService tabPermissions, UserFeaturePermissionService featurePermissions) {
        this.userService = userService;
        this.tabPermissions = tabPermissions;
        this.featurePermissions = featurePermissions;
    }

    @GetMapping("/api/auth/access-signature")
    public Map<String, Object> signature(@AuthenticationPrincipal AuthenticatedUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (user == null) {
            out.put("active", false);
            return out;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(user.role()).append('|').append(user.service());
        String redirectTo = null;
        try {
            var status = userService.teamStatus(user.username());
            redirectTo = status.redirectTo();
            sb.append('|').append(status);
        } catch (RuntimeException ignored) { /* partiel */ }
        try { sb.append('|').append(tabPermissions.resolveForUser(user)); } catch (RuntimeException ignored) { /* partiel */ }
        try { sb.append('|').append(featurePermissions.listForUsername(user.username())); } catch (RuntimeException ignored) { /* partiel */ }
        out.put("active", true);
        out.put("role", user.role());
        out.put("service", user.service());
        out.put("redirectTo", redirectTo);
        out.put("signature", sha(sb.toString()));
        return out;
    }

    static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}

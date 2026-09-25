package com.ecobank.rccportal.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Régression v182 → v187 : Bootstrap passé en local sous /vendor/ sans l'ouvrir dans
 * SecurityConfig — la page de connexion perdait styles et scripts et affichait
 * « Impossible de joindre le serveur RCC ». Chaque ressource de la page de connexion doit
 * être publique.
 */
class LoginPagePublicAssetsTest {

    @Test
    void everyLoginPageAssetFolderIsPublic() throws Exception {
        String security = Files.readString(Path.of("src/main/java/com/ecobank/rccportal/config/SecurityConfig.java"));
        String login = Files.readString(Path.of("src/main/resources/templates/login.html"));
        Matcher m = Pattern.compile("th:(?:src|href)=\"@\\{/([a-z-]+)/").matcher(login);
        int checked = 0;
        while (m.find()) {
            String folder = "\"/" + m.group(1) + "/**\"";
            assertTrue(security.contains(folder), "Ressource de la page de connexion non publique : " + folder);
            checked++;
        }
        assertTrue(checked >= 3);
        String js = Files.readString(Path.of("src/main/resources/static/js/login.js"));
        for (String api : new String[]{"/api/site-settings/login-hero-images", "/api/login-feature-cards/public", "/api/site-settings/public"}) {
            assertTrue(!js.contains(api) || security.contains("\"" + api + "\""), "API de la page de connexion non publique : " + api);
        }
    }
}

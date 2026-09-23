package com.ecobank.rccportal.service;

import com.ecobank.rccportal.config.MfaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Client du serveur MFA Ecobank CI (ecimfa) — vérification OTP.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * Ce serveur MFA ne génère et n'envoie jamais de code lui-même. Le code OTP
 * est produit en local par l'utilisateur via une extension/appli TOTP
 * (type Google/Microsoft Authenticator) déjà configurée en amont, hors du
 * périmètre applicatif RCC. Ce service ne fait QUE VÉRIFIER un code que
 * l'utilisateur a déjà sous les yeux — il n'existe pas et ne doit pas
 * exister de méthode "envoyer" ou "générer" ici.
 *
 * Format de requête (confirmé par le legacy VB SMART_PROFIL.vb) :
 *   POST {activation-path}
 *   Content-Type: text/xml;charset=UTF-8
 *   <tokenrequest>
 *     <user>USERNAME</user>
 *     <token>PASSWORD + CODE_OTP (concaténés, sans séparateur)</token>
 *     <source>NomAppli</source>
 *   </tokenrequest>
 *
 * Réponse : texte brut — "Y" = succès, tout le reste ("N", vide, erreur) =
 * échec. Pas de XML structuré à parser côté réponse.
 *
 * IMPORTANT — le champ <source> et le base-url doivent être confirmés avec
 * l'IT Ecobank pour l'application RCC Portal (les valeurs par défaut de ce
 * fichier proviennent du Closed Loop Service, un autre portail).
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
public class MfaService {

    private final MfaProperties properties;
    private final HttpClient httpClient;

    public MfaService(MfaProperties properties) {
        this.properties = properties;
        this.httpClient = buildHttpClient(properties);
    }

    /**
     * Vérifie le code OTP auprès du serveur MFA.
     *
     * @param username        login SAGED/AD
     * @param passwordPlusOtp mot de passe + code OTP concaténés (sans séparateur)
     * @return true si le serveur MFA répond exactement "Y" ; false pour tout
     *         autre cas (refus, panne réseau, timeout, réponse malformée).
     *         Ne lève jamais d'exception — un problème technique doit être
     *         traité comme un échec de vérification, pas comme un succès.
     */
    public boolean verifierOtp(String username, String passwordPlusOtp) {

        String xml = buildEnvelope(username, passwordPlusOtp, properties.getSource());

        log.info("[MFA] Vérification OTP — user: {}", username);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + properties.getActivationPath()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "text/xml;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[MFA] Le serveur MFA a répondu HTTP {} pour user={}",
                        response.statusCode(), username);
                return false;
            }

            String body = response.body();
            boolean ok = body != null && "Y".equalsIgnoreCase(body.trim());

            log.info("[MFA] Résultat vérification OTP — user: {} | ok: {} | brut: {}",
                    username, ok, body);

            return ok;

        } catch (IOException e) {
            log.error("[MFA] Erreur réseau/timeout — user: {} | erreur: {}",
                    username, e.getMessage());
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[MFA] Appel interrompu — user: {}", username);
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Construction de l'enveloppe XML — format identique au client VB legacy
    // ══════════════════════════════════════════════════════════════════════
    private String buildEnvelope(String user, String token, String source) {
        return "<tokenrequest>"
                + "<user>" + escapeXml(user) + "</user>"
                + "<token>" + escapeXml(token) + "</token>"
                + "<source>" + escapeXml(source) + "</source>"
                + "</tokenrequest>";
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // ══════════════════════════════════════════════════════════════════════
    // SSL — équivalent bloquant de l'InsecureTrustManagerFactory du closed
    // loop. Nécessaire tant que la CA interne Ecobank n'est pas importée
    // dans le trust store du JDK (keytool -importcert). À retirer une fois
    // cela fait, en repassant insecureSsl à false.
    // ══════════════════════════════════════════════════════════════════════
    private static HttpClient buildHttpClient(MfaProperties properties) {

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()));

        if (properties.isInsecureSsl()) {
            log.warn("[MfaService] ⚠ Validation SSL désactivée pour l'appel MFA ({}) — "
                            + "acceptable uniquement en réseau interne fermé vers un hôte connu. "
                            + "À remplacer par un import propre de la CA interne à terme.",
                    properties.getBaseUrl());
            try {
                builder.sslContext(trustAllSslContext());
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new IllegalStateException(
                        "Impossible d'initialiser le SSLContext permissif pour le MFA.", e);
            }
        }

        return builder.build();
    }

    private static SSLContext trustAllSslContext()
            throws NoSuchAlgorithmException, KeyManagementException {

        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        return sslContext;
    }
}

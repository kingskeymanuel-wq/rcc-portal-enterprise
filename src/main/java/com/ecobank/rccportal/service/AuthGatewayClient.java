package com.ecobank.rccportal.service;

import com.ecobank.rccportal.config.AuthGatewayProperties;
import com.ecobank.rccportal.security.AesGcmUtil;
import com.ecobank.rccportal.security.HmacUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.TreeMap;

/**
 * Client de la gateway d'authentification SAGED centralisée — remplace les
 * deux appels séparés historiques (AD SOAP via AdAuthClient/rechercherUserAD,
 * puis MFA via MfaService/ecimfa) par UN SEUL appel signé + chiffré vers
 * {@code rcc.auth.gateway.login-url}, exactement comme la refonte déjà
 * effectuée côté Closed Loop Service (com.ecobank.closedloopservice.service
 * .AuthService) :
 *
 * <ol>
 *   <li>Sérialise {@code {username, password, otp}} en JSON, clés triées
 *       ALPHABÉTIQUEMENT (TreeMap) — le back normalise le JSON avant de
 *       vérifier le HMAC</li>
 *   <li>Calcule HMAC-SHA512(json) → header {@code X-Signature}</li>
 *   <li>Chiffre le JSON via AES-256-GCM → body (Content-Type: text/plain)</li>
 *   <li>POST vers {@code login-url}</li>
 * </ol>
 *
 * ⚠ Changement de comportement assumé, identique à Closed Loop Service : le
 * mot de passe ne peut plus être vérifié seul, avant l'OTP — la gateway exige
 * les 3 valeurs en un seul appel. Un mot de passe erroné n'est donc détecté
 * qu'à l'étape 2 (après saisie de l'OTP), avec un message qui ne distingue
 * pas mot de passe invalide et OTP invalide. Voir AuthService.initiateLogin()
 * / completeLogin() pour l'adaptation du flux 2 étapes du portail à cette
 * contrainte.
 */
@Slf4j
@Service
public class AuthGatewayClient {

    private final AuthGatewayProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AuthGatewayClient(AuthGatewayProperties properties) {
        this.properties = properties;
        this.httpClient = buildHttpClient(properties);
        this.objectMapper = new ObjectMapper();
    }

    public enum Status { OK, INVALID, UNREACHABLE, MISCONFIGURED }

    public record GatewayUser(
            Long id,
            String username,
            String fullName,
            String affiliateCode,
            List<String> roles,
            List<Integer> services,
            List<Integer> permissions,
            String email) {
    }

    public record Result(Status status, GatewayUser user, String accessToken, String message) {
        static Result misconfigured() {
            return new Result(Status.MISCONFIGURED, null, null,
                    "Gateway d'authentification non configurée (rcc.auth.gateway.*).");
        }
        static Result unreachable(String message) {
            return new Result(Status.UNREACHABLE, null, null, message);
        }
        static Result invalid(String message) {
            return new Result(Status.INVALID, null, null, message);
        }
        static Result ok(GatewayUser user, String accessToken) {
            return new Result(Status.OK, user, accessToken, null);
        }
    }

    /** DTO JSON brut de la réponse gateway — voir LoginResponse.java côté Closed Loop Service. */
    private record GatewayLoginResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") int expiresIn,
            GatewayUserDto user) {
    }

    private record GatewayUserDto(
            long id,
            String username,
            String fullName,
            String affiliateCode,
            List<String> roles,
            List<Integer> services,
            List<Integer> permissions,
            String email,
            List<Integer> postingservices) {
    }

    /**
     * Authentifie username+password+otp en un seul appel signé+chiffré.
     * Ne lève jamais d'exception pour un échec d'authentification (INVALID),
     * seulement pour signaler un problème technique (UNREACHABLE) — même
     * contrat que l'ancien AdAuthClient.authenticate().
     */
    public Result login(String username, String password, String otp) {

        if (properties.getLoginUrl() == null || properties.getLoginUrl().isBlank()
                || properties.getHmacSecret() == null || properties.getHmacSecret().isBlank()
                || properties.getAesPassword() == null || properties.getAesPassword().isBlank()) {
            log.error("[AUTH GATEWAY] Configuration incomplète (rcc.auth.gateway.login-url / "
                    + "hmac-secret / aes-password) — voir AuthGatewayProperties.");
            return Result.misconfigured();
        }

        try {
            // Clés triées ALPHABÉTIQUEMENT (otp, password, username) — le back normalise
            // le JSON avant de vérifier le HMAC, voir HmacUtil.
            TreeMap<String, String> sortedPayload = new TreeMap<>();
            sortedPayload.put("username", username);
            sortedPayload.put("password", password);
            sortedPayload.put("otp", otp);
            String json = objectMapper.writeValueAsString(sortedPayload);
            // ⚠ Pas de log du JSON en clair — il contient le mot de passe.

            String signature = HmacUtil.calculate(json, properties.getHmacSecret());
            String encryptedBody = AesGcmUtil.encrypt(json, properties.getAesPassword());

            log.info("[AUTH GATEWAY] Appel login — user: {}", username);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getLoginUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("X-Signature", signature)
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(encryptedBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                log.warn("[AUTH GATEWAY] 401 — user: {} | corps: {}", username, safeBody(response.body()));
                return Result.invalid("Identifiants incorrects ou code incorrect.");
            }
            if (response.statusCode() == 403) {
                log.warn("[AUTH GATEWAY] 403 — user: {}", username);
                return Result.invalid("Accès refusé.");
            }
            if (response.statusCode() / 100 == 5) {
                log.error("[AUTH GATEWAY] Erreur {} — user: {} | corps: {}",
                        response.statusCode(), username, safeBody(response.body()));
                return Result.unreachable("Service d'authentification indisponible.");
            }
            if (response.statusCode() != 200) {
                log.error("[AUTH GATEWAY] Statut inattendu {} — user: {} | corps: {}",
                        response.statusCode(), username, safeBody(response.body()));
                return Result.unreachable("Réponse inattendue du service d'authentification.");
            }

            GatewayLoginResponse parsed = objectMapper.readValue(response.body(), GatewayLoginResponse.class);
            if (parsed.user() == null) {
                log.error("[AUTH GATEWAY] Réponse 200 sans champ 'user' — user: {}", username);
                return Result.unreachable("Réponse du service d'authentification invalide.");
            }

            GatewayUserDto dto = parsed.user();
            GatewayUser user = new GatewayUser(dto.id(), dto.username(), dto.fullName(), dto.affiliateCode(),
                    dto.roles() != null ? dto.roles() : List.of(),
                    dto.services() != null ? dto.services() : List.of(),
                    dto.permissions() != null ? dto.permissions() : List.of(),
                    dto.email());

            log.info("[AUTH GATEWAY] Login OK — user: {} | rôles bruts: {}", username, user.roles());
            return Result.ok(user, parsed.accessToken());

        } catch (IOException e) {
            log.error("[AUTH GATEWAY] Erreur réseau/timeout — user: {} | type: {} | erreur: {} | cible: {}",
                    username, e.getClass().getSimpleName(),
                    e.getMessage() != null ? e.getMessage() : "(aucun message — voir le type d'exception)",
                    properties.getLoginUrl());
            return Result.unreachable("Service d'authentification indisponible. Veuillez réessayer.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[AUTH GATEWAY] Appel interrompu — user: {}", username);
            return Result.unreachable("Service d'authentification indisponible. Veuillez réessayer.");
        } catch (Exception e) {
            log.error("[AUTH GATEWAY] Erreur technique — user: {} | erreur: {}", username, e.getMessage(), e);
            return Result.unreachable("Service d'authentification indisponible. Veuillez réessayer.");
        }
    }

    private static String safeBody(String body) {
        return body == null || body.isBlank() ? "(corps de réponse vide)" : body;
    }

    /** Réponse brute de /api/v1/auth/refresh — mêmes champs que le login (voir Postman "Refresh"). */
    private record GatewayRefreshResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") int expiresIn) {
    }

    public record RefreshResult(boolean ok, String accessToken, String refreshToken, String message) {
        static RefreshResult failure(String message) { return new RefreshResult(false, null, null, message); }
        static RefreshResult success(String accessToken, String refreshToken) { return new RefreshResult(true, accessToken, refreshToken, null); }
    }

    /**
     * Rafraîchit un jeton gateway — appel NON chiffré/signé (JSON en clair), contrairement
     * à login() ; voir Postman "Refresh" : {@code POST refresh-url {refresh_token, username}}.
     */
    public RefreshResult refresh(String username, String refreshToken) {
        if (properties.getRefreshUrl() == null || properties.getRefreshUrl().isBlank()) {
            return RefreshResult.failure("URL de rafraîchissement gateway non configurée (rcc.auth.gateway.refresh-url).");
        }
        try {
            String json = objectMapper.writeValueAsString(java.util.Map.of(
                    "refresh_token", refreshToken, "username", username));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getRefreshUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[AUTH GATEWAY] Refresh échoué ({}) — user: {} | corps: {}", response.statusCode(), username, safeBody(response.body()));
                return RefreshResult.failure("Rafraîchissement du jeton refusé.");
            }
            GatewayRefreshResponse parsed = objectMapper.readValue(response.body(), GatewayRefreshResponse.class);
            return RefreshResult.success(parsed.accessToken(), parsed.refreshToken());
        } catch (Exception e) {
            log.error("[AUTH GATEWAY] Erreur technique refresh — user: {} | erreur: {}", username, e.getMessage());
            return RefreshResult.failure("Service de rafraîchissement indisponible.");
        }
    }

    /**
     * Déconnecte la session côté gateway — voir Postman "Auth Gateway Logout" :
     * {@code POST logout-url}, Authorization Bearer &lt;accessToken&gt;, corps vide. Best-effort :
     * un échec ici ne doit jamais empêcher la déconnexion locale du portail.
     */
    public void logout(String accessToken) {
        if (properties.getLogoutUrl() == null || properties.getLogoutUrl().isBlank() || accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getLogoutUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(""))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[AUTH GATEWAY] Logout gateway best-effort échoué : {}", e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SSL — équivalent bloquant de l'InsecureTrustManagerFactory du Closed
    // Loop Service. Nécessaire tant que la CA interne Ecobank n'est pas
    // importée dans le trust store du JDK.
    // ══════════════════════════════════════════════════════════════════════
    private static HttpClient buildHttpClient(AuthGatewayProperties properties) {

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()));

        if (properties.isInsecureSsl()) {
            log.warn("[AuthGatewayClient] ⚠ Validation SSL désactivée pour l'appel gateway ({}) — "
                            + "acceptable uniquement en réseau interne fermé vers un hôte connu.",
                    properties.getLoginUrl());
            try {
                builder.sslContext(trustAllSslContext());
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new IllegalStateException(
                        "Impossible d'initialiser le SSLContext permissif pour la gateway d'auth.", e);
            }
        }

        return builder.build();
    }

    private static SSLContext trustAllSslContext() throws NoSuchAlgorithmException, KeyManagementException {
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

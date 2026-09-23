package com.ecobank.rccportal.service;

import com.ecobank.rccportal.config.AdAuthProperties;
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
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client SOAP 1.1 pour le service interne Active Directory Ecobank (rechercherUserAD),
 * porté à la main depuis la spécification WSDL fournie (host uniquement joignable depuis
 * le réseau interne Ecobank — voir AdAuthProperties). Pas de framework SOAP (JAX-WS/CXF) :
 * une seule opération, requête XML fixe + extraction de balises, largement suffisant et
 * sans nouvelle dépendance Maven (build offline).
 *
 * ── SSL ─────────────────────────────────────────────────────────────────────
 * epg-eci-apps01 utilise très probablement un certificat auto-signé ou signé par une
 * CA interne Ecobank absente du trust store par défaut du JDK (cacerts) — d'où
 * "PKIX path building failed". Si AdAuthProperties.insecureSsl est vrai, ce client
 * construit un SSLContext permissif (aucune validation de chaîne ni de hostname).
 * ⚠ Acceptable uniquement en réseau interne fermé, vers un hôte connu et fixe. Solution
 * propre à terme : keytool -importcert de la CA interne + insecureSsl=false.
 *
 * IMPORTANT — non vérifiable en conditions réelles depuis un environnement de
 * développement (le host epg-eci-apps01 ne résout même pas en DNS ici). Le parsing de la
 * réponse est testé unitairement contre l'exemple exact de la spécification WSDL fournie.
 */
@Slf4j
@Service
public class AdAuthClient {

    public enum Status { DISABLED, UNREACHABLE, INVALID, VALID }

    public record Result(Status status, String username, String givenName, String surname,
                         String email, String company, String message) {
        static Result disabled() {
            return new Result(Status.DISABLED, null, null, null, null, null, null);
        }
        static Result unreachable(String message) {
            return new Result(Status.UNREACHABLE, null, null, null, null, null, message);
        }
        static Result invalid(String message) {
            return new Result(Status.INVALID, null, null, null, null, null, message);
        }
    }

    private final AdAuthProperties properties;
    private final HttpClient httpClient;

    public AdAuthClient(AdAuthProperties properties) {
        this.properties = properties;

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()));

        if (properties.isInsecureSsl()) {
            log.warn("[AdAuthClient] ⚠ SSL non vérifié pour l'appel AD ({}) — acceptable "
                            + "en réseau interne uniquement. À remplacer par une CA importée (keytool).",
                    properties.getUrl());
            builder.sslContext(insecureSslContext());
        }

        this.httpClient = builder.build();
    }

    public Result authenticate(String username, String password) {
        if (!properties.isEnabled()) return Result.disabled();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", "\"http://tempuri.org/rechercherUserAD\"")
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequest(username, password), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("AD SOAP call returned HTTP {} — treating as unreachable.", response.statusCode());
                return Result.unreachable("AD service returned HTTP " + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (IOException e) {
            log.warn("AD SOAP call unreachable (network/SSL/timeout) — treating as unreachable: {}", e.getMessage());
            return Result.unreachable(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unreachable("Interrupted while calling AD service.");
        }
    }

    String buildRequest(String username, String password) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body>"
                + "<rechercherUserAD xmlns=\"http://tempuri.org/\">"
                + "<user>" + xmlEscape(username) + "</user>"
                + "<pwd>" + xmlEscape(password) + "</pwd>"
                + "<search_user>" + xmlEscape(username) + "</search_user>"
                + "</rechercherUserAD>"
                + "</soap:Body>"
                + "</soap:Envelope>";
    }

    /** À CONFIRMER avec l'IT Ecobank : la valeur exacte de succès (voir AdAuthProperties.successStatus). */
    Result parseResponse(String xml) {
        String status = extractTag(xml, "Status");
        if (status == null) {
            log.warn("Malformed AD response (no <Status> tag) — treating as unreachable.");
            return Result.unreachable("Malformed AD response (no Status field).");
        }
        String message = extractTag(xml, "Message");
        if (!properties.getSuccessStatus().equalsIgnoreCase(status.trim())) {
            return Result.invalid(message);
        }
        return new Result(Status.VALID,
                extractTag(xml, "Username"),
                extractTag(xml, "GivenName"),
                extractTag(xml, "Surname"),
                extractTag(xml, "Email"),
                extractTag(xml, "Compagny"), // orthographe telle que fournie dans le WSDL (pas "Company")
                message);
    }

    /**
     * SSLContext qui fait confiance à tous les certificats + désactive la
     * vérification du hostname (JDK HttpClient). Réservé au réseau interne fermé.
     */
    private static SSLContext insecureSslContext() {
        try {
            // Le JDK HttpClient vérifie le hostname séparément de la chaîne PKIX ;
            // cette propriété système la désactive pour le HttpClient JDK.
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");

            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build insecure SSL context for AD client", e);
        }
    }

    private static String extractTag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        if (!m.find()) return null;
        String value = xmlUnescape(m.group(1));
        return value.isBlank() ? null : value;
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String xmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
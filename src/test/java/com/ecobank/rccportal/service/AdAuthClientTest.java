package com.ecobank.rccportal.service;

import com.ecobank.rccportal.config.AdAuthProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AdAuthClient ne peut pas être testé contre le vrai serveur AD Ecobank depuis cet
 * environnement (epg-eci-apps01 ne résout même pas en DNS ici — voir la note dans
 * AdAuthClient). On vérifie donc : (1) le parsing de réponse contre l'exemple exact fourni
 * dans la spécification WSDL, (2) le round-trip HTTP complet contre un petit serveur local
 * qui rejoue cette même réponse — tout, sauf la jonction réseau réelle vers l'AD Ecobank,
 * qui reste à valider une fois déployé sur le réseau interne.
 */
class AdAuthClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private AdAuthProperties propertiesFor(String url) {
        AdAuthProperties p = new AdAuthProperties();
        p.setUrl(url);
        p.setSuccessStatus("OK");
        p.setTimeoutMs(2000);
        return p;
    }

    @Test
    void disabledWhenNoUrlConfigured() {
        AdAuthClient client = new AdAuthClient(propertiesFor(""));
        var result = client.authenticate("kone.aissatou", "pw");
        assertEquals(AdAuthClient.Status.DISABLED, result.status());
    }

    @Test
    void buildRequestEscapesXmlSpecialCharactersInCredentials() {
        AdAuthClient client = new AdAuthClient(propertiesFor("http://unused/"));
        String xml = client.buildRequest("kone.aissatou", "p&w<d>\"'");
        assertTrue(xml.contains("<user>kone.aissatou</user>"));
        assertTrue(xml.contains("<pwd>p&amp;w&lt;d&gt;&quot;&apos;</pwd>"));
    }

    /** Exemple exact de la spécification WSDL fournie, avec des valeurs réalistes à la place des "string" placeholders. */
    private static final String SUCCESS_RESPONSE = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <rechercherUserADResponse xmlns="http://tempuri.org/">
                  <rechercherUserADResult>
                    <Status>OK</Status>
                    <Username>kone.aissatou</Username>
                    <GivenName>Aissatou</GivenName>
                    <Surname>Kone</Surname>
                    <Email>kone.aissatou@ecobank.com</Email>
                    <Message>Authentication successful</Message>
                    <Compagny>Ecobank</Compagny>
                  </rechercherUserADResult>
                </rechercherUserADResponse>
              </soap:Body>
            </soap:Envelope>
            """;

    private static final String FAILURE_RESPONSE = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <rechercherUserADResponse xmlns="http://tempuri.org/">
                  <rechercherUserADResult>
                    <Status>KO</Status>
                    <Message>Invalid credentials</Message>
                  </rechercherUserADResult>
                </rechercherUserADResponse>
              </soap:Body>
            </soap:Envelope>
            """;

    @Test
    void parsesTheExactWsdlSuccessExample() {
        AdAuthClient client = new AdAuthClient(propertiesFor("http://unused/"));
        var result = client.parseResponse(SUCCESS_RESPONSE);
        assertEquals(AdAuthClient.Status.VALID, result.status());
        assertEquals("kone.aissatou", result.username());
        assertEquals("Aissatou", result.givenName());
        assertEquals("Kone", result.surname());
        assertEquals("kone.aissatou@ecobank.com", result.email());
        assertEquals("Ecobank", result.company());
    }

    @Test
    void parsesAFailureStatusAsInvalidNotAsAnError() {
        AdAuthClient client = new AdAuthClient(propertiesFor("http://unused/"));
        var result = client.parseResponse(FAILURE_RESPONSE);
        assertEquals(AdAuthClient.Status.INVALID, result.status());
        assertEquals("Invalid credentials", result.message());
    }

    @Test
    void treatsAMissingStatusTagAsUnreachableRatherThanSilentlyTrusting() {
        AdAuthClient client = new AdAuthClient(propertiesFor("http://unused/"));
        var result = client.parseResponse("<soap:Envelope><soap:Body>garbage</soap:Body></soap:Envelope>");
        assertEquals(AdAuthClient.Status.UNREACHABLE, result.status());
    }

    @Test
    void fullHttpRoundTripAgainstALocalServerReplayingTheRealResponse() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/RIB_DELIVERY/RIB_DELIVRY.asmx", exchange -> {
            byte[] body = SUCCESS_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        AdAuthClient client = new AdAuthClient(propertiesFor("http://127.0.0.1:" + port + "/RIB_DELIVERY/RIB_DELIVRY.asmx"));
        var result = client.authenticate("kone.aissatou", "correct-password");

        assertEquals(AdAuthClient.Status.VALID, result.status());
        assertEquals("kone.aissatou@ecobank.com", result.email());
    }

    @Test
    void unreachableHostFallsBackGracefullyInsteadOfThrowing() {
        // Port fermé sur localhost -> connexion refusée immédiatement, simule un AD injoignable.
        AdAuthClient client = new AdAuthClient(propertiesFor("http://127.0.0.1:1/unreachable"));
        var result = client.authenticate("kone.aissatou", "pw");
        assertEquals(AdAuthClient.Status.UNREACHABLE, result.status());
    }
}

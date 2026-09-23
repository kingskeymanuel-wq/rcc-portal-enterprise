package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Chemin principal : TranslationService → LibreTranslate local. Un faux serveur HTTP local
 * reproduit l'API LibreTranslate (POST /translate, GET /languages).
 */
class LibreTranslateProviderTest {

    private HttpServer server;
    private TranslationService service;
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/translate", exchange -> {
            JsonNode req = mapper.readTree(exchange.getRequestBody());
            lastRequest.set(req);
            int status;
            String body;
            if ("ha".equals(req.path("target").asText())) {
                status = 400;
                body = "{\"error\":\"ha is not supported\"}";
            } else {
                status = 200;
                body = "{\"translatedText\":\"Your card is ready.\",\"detectedLanguage\":{\"confidence\":92,\"language\":\"fr\"}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.createContext("/languages", exchange -> {
            byte[] bytes = "[{\"code\":\"en\",\"name\":\"English\"},{\"code\":\"fr\",\"name\":\"French\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();

        service = new TranslationService(mock(LocalTranslationClient.class));
        ReflectionTestUtils.setField(service, "providerOrder", "libretranslate,local,custom,deepl,azure,mymemory");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "cacheSize", 0);
        ReflectionTestUtils.setField(service, "myMemoryEnabled", false);
        ReflectionTestUtils.setField(service, "libreUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void translatesThroughLocalLibreTranslateWithNativeAutoDetection() {
        var result = service.translate("Votre carte est prête.", "auto", "en");

        assertEquals("Your card is ready.", result.translatedText());
        assertEquals("fr", result.detectedSourceLang());
        assertEquals("LibreTranslate (local)", result.provider());
        JsonNode req = lastRequest.get();
        assertEquals("auto", req.path("source").asText());
        assertEquals("en", req.path("target").asText());
        assertEquals("text", req.path("format").asText());
    }

    @Test
    void mapsPortalLanguageCodes() {
        service.translate("Bonjour", "fr", "zh-CN");
        assertEquals("zh", lastRequest.get().path("target").asText());
    }

    @Test
    void languageNotLoadedGivesActionableError() {
        ApiException e = assertThrows(ApiException.class, () -> service.translate("Bonjour", "fr", "ha"));
        assertTrue(e.getMessage().contains("ha is not supported"), e.getMessage());
        assertTrue(e.getMessage().contains("LT_LOAD_ONLY"), e.getMessage());
    }

    @Test
    void stoppedServerGivesActionableError() {
        server.stop(0);
        ApiException e = assertThrows(ApiException.class, () -> service.translate("Bonjour", "fr", "en"));
        assertTrue(e.getMessage().contains("LibreTranslate est-il démarré"), e.getMessage());
    }

    @Test
    void unreachableServerIsSkippedInsteadOfWaitingAgain() {
        server.stop(0);
        assertThrows(ApiException.class, () -> service.translate("Bonjour", "fr", "en"));
        ApiException second = assertThrows(ApiException.class, () -> service.translate("Bonsoir", "fr", "en"));
        assertTrue(second.getMessage().contains("nouvel essai dans"), second.getMessage());
    }

    @Test
    void diagnoseListsLoadedLanguages() {
        var row = service.diagnose().get(0);
        assertEquals("LibreTranslate (local)", row.get("source"));
        assertEquals("OK", row.get("status"));
        assertTrue(row.get("detail").contains("Langues chargées : en, fr"), row.get("detail"));
    }
}

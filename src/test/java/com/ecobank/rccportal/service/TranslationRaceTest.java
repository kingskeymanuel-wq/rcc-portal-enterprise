package com.ecobank.rccportal.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TranslationRaceTest {

    private HttpServer slow;
    private HttpServer fast;

    @AfterEach
    void tearDown() {
        if (slow != null) slow.stop(0);
        if (fast != null) fast.stop(0);
    }

    private static HttpServer server(long delayMs, String json) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        s.createContext("/", ex -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        s.start();
        return s;
    }

    @Test
    void slowPreferredSourceDoesNotBlockTheNextOne() throws Exception {
        slow = server(8000, "{\"translatedText\":\"Hello (slow)\"}");
        fast = server(0, "{\"translatedText\":\"Hello\"}");
        TranslationService service = new TranslationService(mock(LocalTranslationClient.class));
        ReflectionTestUtils.setField(service, "providerOrder", "libretranslate,custom");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 10);
        ReflectionTestUtils.setField(service, "cacheSize", 0);
        ReflectionTestUtils.setField(service, "myMemoryEnabled", false);
        ReflectionTestUtils.setField(service, "libreUrl", "http://127.0.0.1:" + slow.getAddress().getPort());
        ReflectionTestUtils.setField(service, "customUrl", "http://127.0.0.1:" + fast.getAddress().getPort() + "/translate");

        long start = System.currentTimeMillis();
        TranslationService.TranslationResult r = service.translate("bonjour tout le monde", "fr", "en");
        long took = System.currentTimeMillis() - start;
        assertEquals("Hello", r.translatedText());
        assertTrue(took < 5000, "trop lent : " + took + " ms");
    }

    @Test
    void offlineGlossaryAnswersCommonPhrasesWhenEverySourceFails() {
        TranslationService service = new TranslationService(mock(LocalTranslationClient.class));
        ReflectionTestUtils.setField(service, "providerOrder", "custom");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 2);
        ReflectionTestUtils.setField(service, "cacheSize", 0);
        ReflectionTestUtils.setField(service, "myMemoryEnabled", false);
        ReflectionTestUtils.setField(service, "customUrl", "http://127.0.0.1:1/translate");
        TranslationService.TranslationResult r = service.translate("Bonjour !", "fr", "en");
        assertEquals("Hello", r.translatedText());
        assertEquals("Merci beaucoup", CommonPhrases.lookup("thank you very much", "en", "fr"));
        assertNull(CommonPhrases.lookup("bonjour madame la directrice", "fr", "en"));
    }
}

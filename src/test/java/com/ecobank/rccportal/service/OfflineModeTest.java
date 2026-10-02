package com.ecobank.rccportal.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Serveur sans Internet : IA et transcription locales (API compatible OpenAI), services Internet coupés. */
class OfflineModeTest {

    private HttpServer server;
    private String base;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void startFakeLocalAi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, "{\"choices\":[{\"message\":{\"content\":\"<think>brouillon</think>Bonjour Madame, votre carte est prête.\"}}]}");
        });
        server.createContext("/v1/audio/transcriptions", ex -> {
            requests.add(ex.getRequestHeaders().getFirst("Content-Type") + "|" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            reply(ex, "{\"text\":\"Bonjour, Ecobank, que puis-je pour vous ?\"}");
        });
        server.createContext("/v1/models", ex -> reply(ex, "{\"data\":[{\"id\":\"qwen2.5:7b\"}]}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private LocalAiClient local() {
        return new LocalAiClient(base, "qwen2.5:7b", "", 30, base, "small", "fr");
    }

    @Test
    void internetAddressesAreRecognised() {
        assertTrue(OfflineMode.isInternetUrl("https://api.anthropic.com/v1/messages"));
        assertTrue(OfflineMode.isInternetUrl("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"));
        assertFalse(OfflineMode.isInternetUrl("http://localhost:5000"));
        assertFalse(OfflineMode.isInternetUrl("http://10.16.1.16:8086/api"));
        assertFalse(OfflineMode.isInternetUrl("http://srv-tiles/{z}/{x}/{y}.png"));
        assertFalse(OfflineMode.isInternetUrl("https://epg-ucce-fin2.ecobank.group/desktop"));
        assertFalse(OfflineMode.isInternetUrl(""));
    }

    @Test
    void localAiAnswersAndTranscribes() throws IOException {
        LocalAiClient ai = local();
        assertEquals("Bonjour Madame, votre carte est prête.", ai.chat("Système", "Question", 200), "réflexion <think> retirée");
        assertTrue(requests.get(0).contains("\"model\":\"qwen2.5:7b\""));
        Path audio = Files.createTempFile("appel", ".wav");
        Files.write(audio, "RIFF-audio".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("Bonjour, Ecobank, que puis-je pour vous ?", ai.transcribe(audio));
        assertTrue(requests.get(1).startsWith("multipart/form-data; boundary="));
        assertTrue(requests.get(1).contains("RIFF-audio") && requests.get(1).contains("name=\"language\""));
        assertEquals(List.of("qwen2.5:7b"), ai.installedModels());
        Files.delete(audio);
    }

    @Test
    void offlineModeUsesLocalAiAndNeverTheCloud() {
        AnthropicClient claude = new AnthropicClient();
        ReflectionTestUtils.setField(claude, "apiKey", "sk-cloud");
        claude.setOfflineMode(new OfflineMode(true));
        assertFalse(claude.isConfigured(), "hors ligne sans IA locale : Anthropic n'est jamais appelé");
        claude.setLocal(local());
        assertTrue(claude.usesLocal());
        assertTrue(claude.isAvailable());
        assertEquals("Bonjour Madame, votre carte est prête.", claude.chat("s", "u", 100));

        AnthropicClient online = new AnthropicClient();
        ReflectionTestUtils.setField(online, "apiKey", "sk-cloud");
        online.setLocal(local());
        assertFalse(online.usesLocal(), "en ligne avec une clé : Anthropic reste prioritaire");
        AnthropicClient noKey = new AnthropicClient();
        noKey.setLocal(local());
        assertTrue(noKey.usesLocal(), "sans clé : IA locale");
    }

    @Test
    void offlineTranslationKeepsOnlyLocalSources() {
        TranslationService t = new TranslationService(new LocalTranslationClient());
        ReflectionTestUtils.setField(t, "providerOrder", "libretranslate,local,custom,deepl,azure,mymemory");
        ReflectionTestUtils.setField(t, "libreUrl", "http://127.0.0.1:5000");
        ReflectionTestUtils.setField(t, "myMemoryEnabled", true);
        ReflectionTestUtils.setField(t, "deeplKey", "cle");
        assertTrue(t.activeProviders().stream().anyMatch(p -> p.contains("MyMemory")));
        t.setOfflineMode(new OfflineMode(true));
        List<String> offline = t.activeProviders();
        assertTrue(offline.stream().anyMatch(p -> p.contains("LibreTranslate")), offline.toString());
        assertTrue(offline.stream().noneMatch(p -> p.contains("MyMemory") || p.contains("DeepL")), offline.toString());
    }

    @Test
    void administratorSwitchTakesEffectImmediately() {
        OfflineMode mode = new OfflineMode(false);
        assertFalse(mode.isEnabled());
        mode.setEnabled(true);
        assertTrue(mode.isEnabled());
        mode.setEnabled(false);
        assertFalse(mode.isEnabled());
    }
}

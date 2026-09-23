package com.ecobank.rccportal.service;

import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TranslationServiceTest {

    private LocalTranslationClient local;
    private TranslationService service;

    @BeforeEach
    void setUp() {
        local = mock(LocalTranslationClient.class);
        service = new TranslationService(local);
        ReflectionTestUtils.setField(service, "providerOrder", "local,custom,libretranslate,deepl,azure,mymemory");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "cacheSize", 10);
        ReflectionTestUtils.setField(service, "myMemoryEnabled", false);
        ReflectionTestUtils.setField(service, "azureEndpoint", "https://example.invalid");
    }

    @Test
    void textAlreadyInTargetLanguageIsReturnedUnchanged() {
        var result = service.translate("Hello, your card has been blocked and we will help you.", "auto", "en");
        assertEquals("Hello, your card has been blocked and we will help you.", result.translatedText());
        assertEquals("en", result.detectedSourceLang());
        verifyNoInteractions(local);
    }

    @Test
    void autoDetectionFeedsProvidersWithoutNativeDetection() {
        when(local.isConfigured()).thenReturn(true);
        when(local.translate(anyString(), eq("fr"), eq("en")))
                .thenReturn(new LocalTranslationClient.LocalTranslateResult("Your card is blocked", "fr"));

        var result = service.translate("Votre carte est bloquée, nous allons vous aider.", "auto", "en");

        assertEquals("Your card is blocked", result.translatedText());
        assertEquals("fr", result.detectedSourceLang());
        assertTrue(result.provider().contains("Argos"));
    }

    @Test
    void sameTextIsServedFromCacheForConsistentResults() {
        when(local.isConfigured()).thenReturn(true);
        when(local.translate(anyString(), anyString(), anyString()))
                .thenReturn(new LocalTranslationClient.LocalTranslateResult("Hello", "fr"));

        service.translate("Bonjour", "fr", "en");
        service.translate("Bonjour", "fr", "en");

        verify(local, times(1)).translate(anyString(), anyString(), anyString());
    }

    @Test
    void failureOnEveryProviderGivesAnExplicitError() {
        when(local.isConfigured()).thenReturn(true);
        when(local.translate(anyString(), anyString(), anyString()))
                .thenThrow(ApiException.serviceUnavailable("paquet fr→ha absent"));

        ApiException e = assertThrows(ApiException.class, () -> service.translate("Bonjour", "fr", "ha"));
        assertTrue(e.getMessage().contains("paquet fr→ha absent"));
    }

    @Test
    void noProviderConfiguredIsReportedClearly() {
        ApiException e = assertThrows(ApiException.class, () -> service.translate("Bonjour", "fr", "en"));
        assertTrue(e.getMessage().contains("Aucune source de traduction"));
    }

    @Test
    void rejectsEmptyAndOversizedText() {
        assertThrows(ApiException.class, () -> service.translate("  ", "fr", "en"));
        assertThrows(ApiException.class, () -> service.translate("a".repeat(TranslationService.MAX_TEXT_LENGTH + 1), "fr", "en"));
    }

    @Test
    void languageCodesAreCanonicalised() {
        assertEquals("zh-CN", TranslationService.canonical("zh"));
        assertEquals("zh-CN", TranslationService.canonical("ZH_cn"));
        assertEquals("pt", TranslationService.canonical("pt-BR"));
        assertEquals("fr", TranslationService.canonical(" FR "));
    }

    @Test
    void htmlEntitiesAreDecoded() {
        assertEquals("L'agence & le client \"VIP\"", TranslationService.unescapeHtml("L&#39;agence &amp; le client &quot;VIP&quot;"));
        assertEquals("é", TranslationService.unescapeHtml("&#xe9;"));
    }

    @Test
    void chunksRespectTheUtf8ByteLimit() {
        String text = "Élément à vérifier très précisément. ".repeat(40);
        List<String> chunks = TranslationService.splitByBytes(text.trim(), 480);
        assertTrue(chunks.size() > 1);
        for (String c : chunks) assertTrue(c.getBytes(StandardCharsets.UTF_8).length <= 480, c);
        assertEquals(text.trim().replaceAll("\\s+", " "), String.join(" ", chunks));
    }
}

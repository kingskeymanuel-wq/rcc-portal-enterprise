package com.ecobank.rccportal.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LanguageDetectorTest {

    @Test
    void detectsCommonLatinLanguages() {
        assertEquals("fr", LanguageDetector.detect("Bonjour, votre carte est bloquée et nous allons vous aider."));
        assertEquals("en", LanguageDetector.detect("Hello, your card has been blocked and we will help you."));
        assertEquals("es", LanguageDetector.detect("Hola, su tarjeta está bloqueada y le vamos a ayudar con la cuenta."));
        assertEquals("pt", LanguageDetector.detect("Olá, o seu cartão está bloqueado e vamos ajudar com a sua conta."));
    }

    @Test
    void detectsNonLatinScripts() {
        assertEquals("ar", LanguageDetector.detect("مرحبا، بطاقتك محظورة"));
        assertEquals("ru", LanguageDetector.detect("Здравствуйте, ваша карта заблокирована"));
        assertEquals("zh-CN", LanguageDetector.detect("您好，您的卡已被冻结"));
        assertEquals("ja", LanguageDetector.detect("こんにちは、カードがブロックされました"));
    }

    @Test
    void returnsNullWhenUndecidable() {
        assertNull(LanguageDetector.detect("12345 !!!"));
        assertNull(LanguageDetector.detect(""));
    }

    @Test
    void frenchAccentHintOnVeryShortText() {
        assertEquals("fr", LanguageDetector.detect("Réclamation"));
    }
}

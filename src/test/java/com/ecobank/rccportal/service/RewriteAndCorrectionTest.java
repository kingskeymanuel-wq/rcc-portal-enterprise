package com.ecobank.rccportal.service;

import com.ecobank.rccportal.repository.WordTermRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Correcteur (meilleure suggestion en premier), réécriture locale sans IA, lecture des réponses Anthropic. */
class RewriteAndCorrectionTest {

    @Test
    void closestSuggestionComesFirst() {
        assertEquals("effectué", LocalSpellCheckClient.rankSuggestions("éfectué", List.of("effectue", "effectué", "effectuer")).get(0));
        assertEquals("reçu", LocalSpellCheckClient.rankSuggestions("recu", List.of("reçut", "recul", "reçu")).get(0));
        assertEquals("patience", LocalSpellCheckClient.rankSuggestions("patiance", List.of("patience", "patienta")).get(0));
    }

    @Test
    void localRewriteCleansFamiliarText() {
        LocalSpellCheckClient sc = new LocalSpellCheckClient(Mockito.mock(WordTermRepository.class));
        TextRewriteService rw = new TextRewriteService(sc, Mockito.mock(AnthropicClient.class));
        var r = rw.rewrite("slt mr, votre carte est bloqué pr cause de pb technique, on va regler ça sous 48h", "fr", "professionnel", true);
        assertTrue(r.text().startsWith("Bonjour Monsieur"), r.text());
        assertTrue(r.text().contains("problème"), r.text());
        assertTrue(r.text().contains("nous allons"), r.text());
        assertTrue(r.text().contains("sous 48"), "« sous 48h » ne doit pas devenir « argent » : " + r.text());
        assertTrue(r.text().endsWith("Cordialement."), r.text());
        assertEquals("Règles locales (sans IA)", r.engine());

        var concise = rw.rewrite("En fait je vous informe que votre virement est arrivé.", "fr", "concis", false);
        assertFalse(concise.text().toLowerCase().contains("en fait"), concise.text());
    }

    @Test
    void anthropicAnswerSkipsThinkingBlocks() throws Exception {
        var json = new ObjectMapper().readTree("""
                {"stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"Bonjour, "},{"type":"text","text":"Madame."}]}""");
        assertEquals("Bonjour, Madame.", AnthropicClient.textOf(json));
    }

    @Test
    void exhaustedCreditGivesShortMessageAndPausesTheAi() {
        AnthropicClient ai = new AnthropicClient();
        org.springframework.test.util.ReflectionTestUtils.setField(ai, "apiKey", "sk-test");
        assertTrue(ai.isAvailable());
        var e = ai.failure(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}");
        assertFalse(e.getMessage().contains("{"), "pas de JSON brut affiché à l'agent : " + e.getMessage());
        assertTrue(e.getMessage().contains("crédit"), e.getMessage());
        assertFalse(ai.isAvailable(), "plus d'appel (ni d'attente) tant que le crédit n'est pas rechargé");

        LocalSpellCheckClient sc = new LocalSpellCheckClient(Mockito.mock(WordTermRepository.class));
        var r = new TextRewriteService(sc, ai).rewrite("merci de traiter ce dossier", "fr", "professionnel", true);
        assertEquals("Règles locales (sans IA)", r.engine());
        assertTrue(r.changes().get(0).startsWith("IA indisponible (crédit"), r.changes().get(0));
    }
}

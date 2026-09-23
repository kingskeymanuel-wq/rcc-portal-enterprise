package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.WordTerm;
import com.ecobank.rccportal.repository.WordTermRepository;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** LanguageTool embarqué — instance partagée (coûteuse à construire). */
class LocalSpellCheckClientTest {

    private static LocalSpellCheckClient client;

    @BeforeAll
    static void setUp() {
        WordTermRepository repo = mock(WordTermRepository.class);
        when(repo.findByActiveTrueOrderByTermAsc()).thenReturn(List.of(
                WordTerm.builder().term("CASHXPRESS").definition("x").build(),
                WordTerm.builder().term("Ecobankpay").definition("x").build()));
        client = new LocalSpellCheckClient(repo);
        ReflectionTestUtils.setField(client, "remoteUrl", "");
        ReflectionTestUtils.setField(client, "disabledRulesConfig", "NOMBRES_EN_LETTRES,NOMBRES_EN_LETTRES_2,FRENCH_WHITESPACE,APOS_TYP");
        ReflectionTestUtils.setField(client, "ignoreWordsConfig", "Ecobank,Rapidtransfer,Xpress");
    }

    private List<String> flagged(String text, String lang) {
        var result = client.check(text, lang);
        return result.issues().stream().map(i -> text.substring(i.offset(), i.offset() + i.length())).toList();
    }

    @Test
    void findsRealFrenchMistakes() {
        List<String> flagged = flagged("Les dossier sont traiter par l'agence.", "fr");
        assertTrue(flagged.stream().anyMatch(s -> s.contains("dossier")), flagged.toString());
        assertTrue(flagged.stream().anyMatch(s -> s.contains("traiter")), flagged.toString());
    }

    @Test
    void doesNotFlagBusinessVocabularyAcronymsOrNumbers() {
        List<String> flagged = flagged(
                "Le client utilise Ecobank Xpress et Rapidtransfer. Il a reçu un OTP pour CASHXPRESS, réf TRX4589, via Ecobankpay.", "fr");
        for (String word : List.of("Ecobank", "Xpress", "Rapidtransfer", "OTP", "CASHXPRESS", "TRX4589", "Ecobankpay")) {
            assertFalse(flagged.contains(word), word + " ne doit pas être signalé : " + flagged);
        }
    }

    @Test
    void doesNotFlagPureStyleRules() {
        List<String> flagged = flagged("Le client a fait 3 tentatives : la carte est bloquée.", "fr");
        assertFalse(flagged.contains("3"), flagged.toString());
    }

    @Test
    void issuesAreSortedAndNeverOverlap() {
        var issues = client.check("Je suis alle a la agence hier et les dossier sont traiter.", "fr").issues();
        for (int i = 1; i < issues.size(); i++) {
            assertTrue(issues.get(i).offset() >= issues.get(i - 1).offset() + issues.get(i - 1).length());
        }
    }

    @Test
    void englishAndAutoDetection() {
        assertTrue(flagged("I recieve the transfer yesterday.", "en-US").contains("recieve"));
        var auto = client.check("Hello, I recieve the money from your bank yesterday and it is ok.", "auto");
        assertTrue(auto.language().startsWith("en"));
    }

    @Test
    void unsupportedLanguageWithoutRemoteServerIsExplicit() {
        assertThrows(ApiException.class, () -> client.check("Hola", "es"));
    }

    @Test
    void concurrentChecksReturnConsistentResults() throws Exception {
        String text = "Les dossier sont traiter.";
        int expected = client.check(text, "fr").issues().size();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> tasks = java.util.stream.IntStream.range(0, 24)
                    .mapToObj(i -> (Callable<Integer>) () -> client.check(text, "fr").issues().size()).toList();
            for (Future<Integer> f : pool.invokeAll(tasks)) assertEquals(expected, f.get());
        } finally {
            pool.shutdownNow();
        }
    }
}

package com.ecobank.rccportal.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pertinence des barres de recherche — logique pure, sans mock. */
class SearchTextTest {

    private SearchText.Match match(String query, String title, String content) {
        List<String> terms = SearchText.queryTerms(query);
        return SearchText.score(query, terms, SearchText.Field.of(title, 3.0), SearchText.Field.of(content, 1.0));
    }

    @Test
    void ignoresAccentsAndCase() {
        assertTrue(match("procedure", "Procédure de blocage", "").isRelevant(1));
        assertTrue(match("PROCÉDURE", "procedure de blocage", "").isRelevant(1));
    }

    @Test
    void matchesPluralAndFeminineForms() {
        var m = match("cartes bloquées", "Blocage de carte : carte bloqué par le client", "");
        assertTrue(m.isRelevant(2));
        assertEquals(1.0, m.coverage());
    }

    @Test
    void multiWordQueryDoesNotNeedExactPhrase() {
        assertTrue(match("carte bloquée", "Déblocage", "Si la carte du client est bloquée, vérifier l'identité.").isRelevant(2));
    }

    @Test
    void shortFragmentDoesNotMatchInsideAnotherWord() {
        // L'ancien contains() faisait remonter « carte » pour la recherche « art ».
        assertFalse(match("art", "Carte Visa", "").isRelevant(1));
    }

    @Test
    void toleratesSmallTypos() {
        assertTrue(match("virment", "Virement international", "").isRelevant(1));
        assertTrue(match("reclamation", "Réclamations clients", "").isRelevant(1));
    }

    @Test
    void titleMatchScoresHigherThanContentMatch() {
        double inTitle = match("virement", "Virement", "").score();
        double inContent = match("virement", "Autre sujet", "virement").score();
        assertTrue(inTitle > inContent);
    }

    @Test
    void requiresAtLeastHalfOfTheTermsForMultiWordQueries() {
        assertFalse(match("carte bloquee opposition urgence", "Carte", "").isRelevant(4));
    }

    @Test
    void stopwordsOnlyQueryHasNoTerms() {
        assertTrue(SearchText.queryTerms("comment le la de").isEmpty());
    }

    @Test
    void snippetIsCenteredOnTheMatch() {
        String text = "x".repeat(300) + " virement urgent " + "y".repeat(300);
        String snippet = SearchText.snippetAround(text, List.of("virement"), 100);
        assertTrue(snippet.contains("virement"));
        assertTrue(snippet.length() <= 102);
    }

    @Test
    void stripsHtml() {
        assertEquals("Titre gras & fin", SearchText.stripHtml("<h1>Titre</h1><script>alert(1)</script><b>gras</b> &amp; fin"));
    }
}

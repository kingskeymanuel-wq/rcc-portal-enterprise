package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.WebSearchResultItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DuckDuckGoParseTest {

    @Test
    void parsesRealResultsAndDecodesRedirectLinks() {
        String html = """
                <div class="result results_links"><h2 class="result__title">
                <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fecobank.com%2Fci%2Fpersonal&amp;rut=abc">Ecobank <b>Côte d'Ivoire</b></a></h2>
                <a class="result__snippet" href="x">Ouvrez un compte &amp; gérez vos <b>cartes</b>.</a></div>
                <div class="result"><a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad=1">Pub</a></div>
                <div class="result"><a rel="nofollow" class="result__a" href="https://fr.wikipedia.org/wiki/Ecobank">Ecobank — Wikipédia</a>
                <a class="result__snippet" href="y">Groupe bancaire panafricain.</a></div>
                """;
        List<WebSearchResultItem> r = WebSearchClient.parseDuckDuckGo(html, 5);
        assertEquals(2, r.size());
        assertEquals("https://ecobank.com/ci/personal", r.get(0).url());
        assertEquals("Ecobank Côte d'Ivoire", r.get(0).title());
        assertEquals("Ouvrez un compte & gérez vos cartes.", r.get(0).snippet());
        assertEquals("https://fr.wikipedia.org/wiki/Ecobank", r.get(1).url());
    }

    @Test
    void captchaPageGivesNoResult() {
        assertTrue(WebSearchClient.parseDuckDuckGo("<html>anomaly-modal</html>", 5).isEmpty());
    }
}

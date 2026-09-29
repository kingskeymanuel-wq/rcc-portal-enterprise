package com.ecobank.rccportal.raf;

import com.ecobank.rccportal.dto.WebSearchResultItem;
import com.ecobank.rccportal.service.DataProtectionService;
import com.ecobank.rccportal.service.WebSearchClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** RAF tire l'information des pages web et la rédige, au lieu d'afficher des liens (souvent hors sujet). */
class RafHumanWebAnswerTest {

    private static final List<WebSearchResultItem> RESULTS = List.of(
            new WebSearchResultItem("Le Client (roman) — Wikipédia", "Le Client est un thriller publié en 1993 par l&#039;auteur John Grisham.", "https://fr.wikipedia.org/wiki/Le_Client"),
            new WebSearchResultItem("Belladone — Wikipédia", "baies noires contenant de l&#039;atropine, substance active sur le système nerveux.", "https://fr.wikipedia.org/wiki/Belladone"),
            new WebSearchResultItem("Carte Visa prépayée Ecobank", "La carte Visa prépayée Ecobank permet de payer en ligne. Le plafond de retrait est de 500 000 FCFA par jour.", "https://ecobank.com/ci/carte-prepayee"),
            new WebSearchResultItem("Plafonds carte prépayée", "Plafond de la carte prépayée : paiements jusqu'à 1 000 000 FCFA par mois.", "https://www.exemple.ci/plafonds"));

    private final List<String> terms = List.of("plafond", "carte", "prepayee");

    @Test
    void offTopicPagesAreDropped() {
        List<WebSearchResultItem> kept = RafWebResearch.relevant(RESULTS, terms);
        assertEquals(2, kept.size());
        assertTrue(kept.get(0).url().contains("ecobank"), "site officiel en tête");
        assertTrue(kept.stream().noneMatch(r -> r.url().contains("wikipedia")));
        assertTrue(RafWebResearch.relevant(RESULTS, List.of("swift", "virement")).isEmpty());
    }

    @Test
    void theAnswerIsWrittenNotAListOfLinks() {
        DataProtectionService dp = mock(DataProtectionService.class);
        when(dp.sanitize(anyString())).thenAnswer(i -> i.getArgument(0));
        RafWebResearch web = new RafWebResearch(mock(WebSearchClient.class), dp);
        String md = web.humanAnswer("plafond carte prépayée", RafWebResearch.relevant(RESULTS, terms), terms, "fr");
        assertTrue(md.contains("500 000 FCFA"), md);
        assertTrue(md.contains("site officiel d'Ecobank"));
        assertFalse(md.contains("https://"), "aucun lien à ouvrir");
        assertTrue(md.contains("Sources consultées : ecobank.com"));
    }

    @Test
    void htmlEntitiesAreCleaned() {
        assertEquals("l'atropine & co", RafWebResearch.clean("l&#039;atropine &amp; co"));
    }
}

package com.ecobank.rccportal.raf;

import com.ecobank.rccportal.dto.WebSearchResultItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RafWebResearchTest {

    @Test
    void detectsExplicitWebRequests() {
        assertEquals("les frais swift", RafWebResearch.explicitQuery("cherche sur internet les frais swift"));
        assertEquals("taux de change euro", RafWebResearch.explicitQuery("Recherche sur le web : taux de change euro"));
        assertEquals("horaires ecobank dimanche", RafWebResearch.explicitQuery("horaires ecobank dimanche sur internet ?"));
        assertEquals("code swift ecobank", RafWebResearch.explicitQuery("google: code swift ecobank"));
        assertNull(RafWebResearch.explicitQuery("comment bloquer une carte"));
        assertNull(RafWebResearch.explicitQuery("le client ne trouve pas le web code cash xpress"));
    }

    @Test
    void answerIsSourcedAndFlaggedAsExternal() {
        String md = RafWebResearch.markdown("code swift", List.of(
                new WebSearchResultItem("Code SWIFT Ecobank CI", "Le code SWIFT d'Ecobank Côte d'Ivoire est ECOCCIAB.", "https://ecobank.com/ci/swift"),
                new WebSearchResultItem("SWIFT — Wikipédia", "Le code SWIFT/BIC identifie une banque.", "https://fr.wikipedia.org/wiki/SWIFT")), true, "fr");
        assertTrue(md.contains("sur le web"));
        assertTrue(md.contains("ECOCCIAB"));
        assertTrue(md.contains("site officiel Ecobank"));
        assertTrue(md.contains("Source externe"));
    }
}

package com.ecobank.rccportal.raf;

import com.ecobank.rccportal.raf.RafDocs.*;
import com.ecobank.rccportal.raf.agent.SlaCalculator;
import com.ecobank.rccportal.raf.nlp.EntityExtractor;
import com.ecobank.rccportal.raf.nlp.FollowUpResolver;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Calcul d'échéance SLA et extraction d'entités — logique pure. */
class RafNlpTest {

    private static SlaDoc sla(int hours, String label) {
        return new SlaDoc(1, "m", "c", null, hours, label, null, null, false, null);
    }

    @Test
    void businessDaysSkipWeekends() {
        LocalDateTime friday = LocalDateTime.of(2026, 9, 25, 10, 0);
        assertEquals(LocalDateTime.of(2026, 9, 29, 10, 0), SlaCalculator.dueDate(friday, sla(48, "2 jours ouvrés")).orElseThrow());
        LocalDateTime saturday = LocalDateTime.of(2026, 9, 26, 10, 0);
        assertEquals(LocalDate.of(2026, 9, 29), SlaCalculator.dueDate(saturday, sla(24, "1 jour ouvré")).orElseThrow().toLocalDate());
    }

    @Test
    void hoursAndConditionalLabels() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 22, 14, 30);
        assertEquals(t.plusHours(24), SlaCalculator.dueDate(t, sla(24, "24 heures")).orElseThrow());
        assertTrue(SlaCalculator.dueDate(t, sla(24, "24h si le client fournit le justificatif")).isEmpty());
        assertTrue(SlaCalculator.dueDate(t, sla(0, "Instantané")).isEmpty());
    }

    @Test
    void extractsCountryDateStepLevelAndTemplateFields() {
        RafCatalog catalog = RafCatalog.fixed(new Snapshot(List.of(), List.of(), List.of(),
                List.of(new BranchDoc(1, "CI", "Abidjan", "A", null, null, null, null, null, null)),
                List.of(new CountryDoc("CI", "Côte d'Ivoire", null, Set.of("cote d ivoire", "ivory coast")),
                        new CountryDoc("SN", "Sénégal", null, Set.of("senegal"))),
                List.of(), List.of(), List.of(), List.of()));
        EntityExtractor ex = new EntityExtractor(catalog);
        LocalDate today = LocalDate.of(2026, 9, 22); // mardi
        var e = ex.extract("procédure N2 étape 3 en Côte d'Ivoire demain pour Mme Koné, montant 50 000 FCFA, réf TRX123", today);
        assertEquals("CI", e.countryCode());
        assertEquals(today.plusDays(1), e.date());
        assertEquals(3, e.stepNumber());
        assertEquals("N2", e.level());
        assertEquals("Mme Koné", e.slotValues().get("NOM"));
        assertEquals("50 000 FCFA", e.slotValues().get("MONTANT"));
        assertEquals("TRX123", e.slotValues().get("REFERENCE"));
        assertEquals(LocalDate.of(2026, 9, 25), ex.extract("mon planning vendredi", today).date());
        assertEquals("Abidjan", ex.extract("agence à Abidjan", today).city());
    }

    @Test
    void ellipticalFollowUps() {
        assertTrue(FollowUpResolver.isElliptical("et pour le senegal"));
        assertTrue(FollowUpResolver.isElliptical("et en n2"));
        assertFalse(FollowUpResolver.isElliptical("procedure opposition carte"));
    }
}

package com.ecobank.rccportal.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PersonNamesTest {

    @Test
    void sameAgentWrittenDifferentlyIsRecognized() {
        assertTrue(PersonNames.similar("MOKE Marie Carmella", "MOKE Marie Carmella (2°)"));
        assertTrue(PersonNames.similar("MOKE CARMELA", "MOKE Marie Carmella (2°)"));
        assertTrue(PersonNames.similar("Kouassi Jean", "JEAN KOUASSI"));
        assertTrue(PersonNames.similar("Élodie Brou", "ELODIE BROU"));
        assertEquals(PersonNames.key("MOKE Marie Carmella"), PersonNames.key("Marie MOKE Carmella (2°)"));
    }

    @Test
    void differentPeopleAreNotMerged() {
        assertFalse(PersonNames.similar("MOKE Marie", "MOKE Paul"));
        assertFalse(PersonNames.similar("Kouassi", "Kouassi Jean")); // un seul mot : trop peu sûr
        assertFalse(PersonNames.similar("Yao Ali", "Yao Alain"));    // mots courts : pas de tolérance de faute
    }

    @Test
    void findUniqueRefusesAmbiguousMatches() {
        Function<String, String> id = Function.identity();
        assertEquals("MOKE Marie Carmella", PersonNames.findUnique("MOKE CARMELA", List.of("MOKE Marie Carmella", "Kouassi Jean"), id));
        assertNull(PersonNames.findUnique("KOUASSI JEAN", List.of("Kouassi Jean Marc", "Kouassi Jean Paul"), id));
        assertNull(PersonNames.findUnique("Inconnu Total", List.of("MOKE Marie Carmella"), id));
    }
}

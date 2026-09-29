package com.ecobank.rccportal.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Mémoire RAF persistée : jamais de numéro de compte, de carte ou de téléphone en clair. */
class RafMemoryPersistenceTest {

    @Test
    void numbersAreMaskedBeforeStorage() {
        assertEquals("Carte •••••• bloquée", RafConversationMemoryService.mask("Carte 4512 3456 7890 1234 bloquée"));
        assertEquals("Appeler le ••••••", RafConversationMemoryService.mask("Appeler le 07 07 12 34 56"));
        assertEquals("Compte ••••••", RafConversationMemoryService.mask("Compte 0123456789"));
        assertEquals("Délai de 48 h, code 1234", RafConversationMemoryService.mask("Délai de 48 h, code 1234"), "les petits nombres restent lisibles");
        assertNull(RafConversationMemoryService.mask(null));
    }

    @Test
    void withoutDatabaseTheMemoryStillWorks() {
        RafConversationMemoryService m = new RafConversationMemoryService();
        m.record("awa", "Comment débloquer une carte ?", "Étapes…");
        assertEquals(1, m.history("awa").size());
        assertEquals(1, m.today("awa").size());
        m.clear("awa");
        assertTrue(m.today("awa").isEmpty());
    }
}

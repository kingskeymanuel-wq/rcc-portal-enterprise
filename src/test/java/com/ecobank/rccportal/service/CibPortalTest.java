package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.KnowledgeCategory;
import com.ecobank.rccportal.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Portail CIB : redirection des agents CIB et séparation de la base de connaissance CIB. */
class CibPortalTest {

    @Test
    void cibAgentsLandOnTheirPortal() {
        assertEquals("/portail-cib", UserService.digitalChannelPortal("CIB"));
        assertEquals("/portail-cib", UserService.digitalChannelPortal("CMB CIB"));
        assertEquals("/portail-cib", UserService.digitalChannelPortal("Agent CIB"));
        assertNull(UserService.digitalChannelPortal("INBOUND VOICE"));
        assertTrue(UserService.AGENT_PORTALS.contains("/portail-cib"));
    }

    @Test
    void cibBaseIsSeparatedFromTheGeneralBase() {
        KnowledgeCategory cib = KnowledgeCategory.builder().team("CIB").build();
        KnowledgeCategory general = KnowledgeCategory.builder().team(null).build();
        KnowledgeCategory voice = KnowledgeCategory.builder().team("INBOUND_VOICE").build();

        assertTrue(KnowledgeService.inSpace(cib, "CIB"));
        assertFalse(KnowledgeService.inSpace(general, "CIB"));
        assertFalse(KnowledgeService.inSpace(voice, "CIB"));

        assertFalse(KnowledgeService.inSpace(cib, "GENERAL"));
        assertTrue(KnowledgeService.inSpace(general, "GENERAL"));
        assertTrue(KnowledgeService.inSpace(voice, "GENERAL"));

        assertTrue(KnowledgeService.inSpace(cib, null));
        assertTrue(KnowledgeService.inSpace(general, null));
    }

    @Test
    void onlyManagementChoosesTheBase() {
        assertTrue(KnowledgeService.isKbManager(new AuthenticatedUser("a", "admin", null, "A")));
        assertTrue(KnowledgeService.isKbManager(new AuthenticatedUser("q", "agent", "QUALITY_ASSURANCE", "Q")));
        assertTrue(KnowledgeService.isKbManager(new AuthenticatedUser("s", "supervisor", null, "S")));
        assertFalse(KnowledgeService.isKbManager(new AuthenticatedUser("g", "agent", "AGENT_CIB", "G")));
        assertFalse(KnowledgeService.isKbManager(new AuthenticatedUser("t", "team_leader", "TEAM_LEADER_CIB", "T")));
    }
}

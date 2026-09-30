package com.ecobank.rccportal.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Rôle agent ↔ service ↔ équipe : une seule correspondance, dans les deux sens. */
class AgentTeamSyncTest {

    @Test
    void rolesAndServicesPointToTheSameTeam() {
        assertEquals("INBOUND_MAIL", AdministrationService.agentTeamOfRole("Agent Inbound Mail"));
        assertEquals("INBOUND_VOICE", AdministrationService.agentTeamOfRole("Agent Inbound Voice"));
        assertEquals("TELEVENTE", AdministrationService.agentTeamOfRole("Agent Télévente"));
        assertEquals("TELEVENTE", AdministrationService.agentTeamOfRole("Télévente"));
        assertEquals("DIGITALISATION", AdministrationService.agentTeamOfRole("Agent Digitalisation"));
        assertEquals("TCHAT", AdministrationService.agentTeamOfRole("Réseaux sociaux"));
        assertEquals("OUTBOUND", AdministrationService.agentTeamOfRole("Agent Outbound"));
        assertNull(AdministrationService.agentTeamOfRole("Team Leader Inbound Mail"));
        assertNull(AdministrationService.agentTeamOfRole("Agent Inbound"), "rôle générique : pas d'équipe imposée");
        assertNull(AdministrationService.agentTeamOfRole("Quality Assurance"));
        for (String[] t : AdministrationService.AGENT_TEAMS) {
            assertEquals(t[0], AdministrationService.agentTeamOfRole(t[1]), t[1]);
            assertEquals(t[0], AdministrationService.agentTeamOfService(t[2]), t[2]);
            assertEquals(t[2], AdministrationService.serviceForRoleName(t[1]), t[1]);
        }
        assertEquals("TEAM_LEADER_OUTBOUND", AdministrationService.serviceForRoleName("Team Leader Télévente"));
        assertEquals(com.ecobank.rccportal.util.TeamClassifier.Team.OUTBOUND,
                com.ecobank.rccportal.util.TeamClassifier.classify("OUTBOUND TELEVENTE"));
        assertEquals("TEAM_LEADER_OUTBOUND", AdministrationService.serviceForRoleName("Team Leader Digitalisation"));
        assertEquals(com.ecobank.rccportal.util.TeamClassifier.Team.OUTBOUND,
                com.ecobank.rccportal.util.TeamClassifier.classify("OUTBOUND DIGITALISATION"));
        assertTrue(AdministrationService.OUTBOUND_SUBTEAMS.containsAll(java.util.List.of("TELEVENTE", "DIGITALISATION")));
    }
}

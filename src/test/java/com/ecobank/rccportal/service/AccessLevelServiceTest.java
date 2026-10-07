package com.ecobank.rccportal.service;

import com.ecobank.rccportal.security.AccessResolver;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Changement de niveau d'accès en un geste : chaque niveau donne exactement l'accès voulu, sans reste de l'ancien. */
class AccessLevelServiceTest {

    /** Accès obtenu si l'on attribue le premier rôle proposé de chaque groupe et les services du plan. */
    private static String resultingLevel(AccessLevelService.Plan p) {
        List<String> roles = p.roles().stream().map(c -> c.get(0)).toList();
        String profile = AccessResolver.primaryRole(roles, p.services(), p.ledTeam());
        return AdminHierarchyService.levelOf(profile, p.services());
    }

    @Test
    void teamLeaderBackToAgentLosesTheLedTeam() {
        AccessLevelService.Plan p = AccessLevelService.plan("AGENT", "INBOUND_VOICE");
        assertNull(p.ledTeam());
        assertEquals(List.of("AGENT_INBOUND"), p.services());
        assertEquals("INBOUND VOICE", p.activity());
        assertEquals("AGENT", resultingLevel(p));
        assertEquals("INBOUND_VOICE", AdminHierarchyService.teamOf("AGENT", null, p.activity(), p.services()));
    }

    @Test
    void agentPromotedToTeamLeaderLeadsTheChosenTeam() {
        AccessLevelService.Plan p = AccessLevelService.plan("team_leader", "tchat");
        assertEquals("TCHAT", p.ledTeam());
        assertEquals(List.of("TEAM_LEADER_TCHAT"), p.services());
        assertEquals("TEAM_LEADER", resultingLevel(p));
    }

    @Test
    void everyLevelGivesItsOwnAccess() {
        assertEquals("QA", resultingLevel(AccessLevelService.plan("QA", null)));
        assertEquals("QA", resultingLevel(AccessLevelService.plan("FORMATEUR", null)));
        assertEquals("HEAD_QA", resultingLevel(AccessLevelService.plan("HEAD_QA", null)));
        assertEquals("SUPERVISEUR", resultingLevel(AccessLevelService.plan("SUPERVISEUR", null)));
        assertEquals("RH", resultingLevel(AccessLevelService.plan("RH", null)));
        assertEquals("AGENCE", resultingLevel(AccessLevelService.plan("AGENCE", "GESTIONNAIRE")));
        assertEquals("ADMIN", resultingLevel(AccessLevelService.plan("ADMIN", null)));
        for (String t : AccessLevelService.TEAMS) {
            assertEquals("AGENT", resultingLevel(AccessLevelService.plan("AGENT", t)));
            assertEquals("TEAM_LEADER", resultingLevel(AccessLevelService.plan("TEAM_LEADER", t)));
        }
    }

    @Test
    void agentOrTeamLeaderNeedsATeam() {
        assertThrows(ApiException.class, () -> AccessLevelService.plan("AGENT", null));
        assertThrows(ApiException.class, () -> AccessLevelService.plan("TEAM_LEADER", "INCONNUE"));
        assertThrows(ApiException.class, () -> AccessLevelService.plan("PATRON", null));
    }

    @Test
    void onlyPortalRolesAndServicesAreReplaced() {
        assertTrue(AccessLevelService.isPortalRole("Team Leader Inbound Voice"));
        assertTrue(AccessLevelService.isPortalRole("agent"));
        assertTrue(AccessLevelService.isPortalRole("Superviseur Qualité Assurance"));
        assertTrue(AccessLevelService.isPortalRole("admin"));
        assertFalse(AccessLevelService.isPortalRole("Head Outbound"));
        assertTrue(AccessLevelService.isPortalService("TEAM_LEADER_RAFIKI"));
        assertTrue(AccessLevelService.isPortalService("agent_tchat"));
        assertTrue(AccessLevelService.isPortalService("SUPERVISEUR_QA"));
        assertFalse(AccessLevelService.isPortalService("COMMUNICATION"));
        assertFalse(AccessLevelService.isPortalService(null));
    }
}

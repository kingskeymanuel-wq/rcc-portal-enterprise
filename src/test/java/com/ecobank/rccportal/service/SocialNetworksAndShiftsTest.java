package com.ecobank.rccportal.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Rôle « Réseaux sociaux » → portail ex-Tchat ; catalogue des shifts. */
class SocialNetworksAndShiftsTest {

    @Test
    void socialNetworkRoleGivesTheTchatPortal() {
        assertEquals("AGENT_TCHAT", AdministrationService.serviceForRoleName("Agent Réseaux sociaux"));
        assertEquals("AGENT_TCHAT", AdministrationService.serviceForRoleName("Réseaux sociaux"));
        assertEquals("AGENT_TCHAT", AdministrationService.serviceForRoleName("Agent Tchat"));
        assertEquals("AGENT_RAFIKI", AdministrationService.serviceForRoleName("Agent Rafiki"));
        assertEquals("TEAM_LEADER_TCHAT", AdministrationService.serviceForRoleName("Team Leader Réseaux sociaux"));
        assertEquals("AGENT_INBOUND", AdministrationService.serviceForRoleName("Agent Inbound Voice"));
        assertEquals("/portail-tchat", UserService.digitalChannelPortal("Réseaux sociaux"));
        assertEquals("/portail-tchat", UserService.digitalChannelPortal("INBOUND TCHAT"));
        assertEquals("/portail-rafiki", UserService.digitalChannelPortal("Agent Rafiki"));
        assertNull(UserService.digitalChannelPortal("Agent Inbound Voice"));
    }

    @Test
    void shiftLabels() {
        assertEquals("Matin 07h-16h", ShiftCatalogService.defaultLabel("M", "07:00", "16:00"));
        assertEquals("Nuit 21h-07h", ShiftCatalogService.defaultLabel("N", "21:00", "07:00"));
        assertEquals("Matin 09h30-18h", ShiftCatalogService.defaultLabel("M5", "09:30", "18:00"));
        assertEquals(6, ShiftCatalogService.SEED.size());
    }
}

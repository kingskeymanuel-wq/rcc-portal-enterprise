package com.ecobank.rccportal.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Choisir un rôle dans l'Administration donne aussi son service métier (équipe dirigée d'un Team Leader, profil QA…). */
class AdminRoleServiceTest {

    @Test
    void roleNamesImplyTheirService() {
        assertEquals("TEAM_LEADER_INBOUND_VOICE", AdministrationService.serviceForRoleName("Team Leader Inbound Voice"));
        assertEquals("TEAM_LEADER_INBOUND_MAIL", AdministrationService.serviceForRoleName("Team Leader Inbound Mail"));
        assertEquals("TEAM_LEADER_TCHAT", AdministrationService.serviceForRoleName("Team Leader Tchat"));
        assertEquals("TEAM_LEADER_RAFIKI", AdministrationService.serviceForRoleName("Team Leader Rafiki"));
        assertEquals("TEAM_LEADER_OUTBOUND", AdministrationService.serviceForRoleName("Team Leader Outbound"));
        assertEquals("SUPERVISEUR", AdministrationService.serviceForRoleName("Head RCC (Superviseur)"));
        assertEquals("SUPERVISEUR_QA", AdministrationService.serviceForRoleName("Superviseur Qualité Assurance"));
        assertEquals("QUALITY_ASSURANCE", AdministrationService.serviceForRoleName("Quality Assurance"));
        assertEquals("RH", AdministrationService.serviceForRoleName("RH"));
        assertNull(AdministrationService.serviceForRoleName("team_leader"));   // équipe à choisir : signalé dans « Accès effectif »
        assertNull(AdministrationService.serviceForRoleName("agent"));
    }
}

package com.ecobank.rccportal.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Organigramme de l'administration : niveau et équipe de chacun, groupement par équipe. */
class AdminHierarchyTest {

    @Test
    void levelFollowsTheRealAccess() {
        assertEquals("SUPERVISEUR", AdminHierarchyService.levelOf("SUPERVISOR", List.of("SUPERVISEUR")));
        assertEquals("RH", AdminHierarchyService.levelOf("RH", List.of("RH")));
        assertEquals("HEAD_QA", AdminHierarchyService.levelOf("AGENT", List.of("SUPERVISEUR_QA")));
        assertEquals("QA", AdminHierarchyService.levelOf("AGENT", List.of("QUALITY_ASSURANCE")));
        assertEquals("QA", AdminHierarchyService.levelOf("AGENT", List.of("FORMATEUR")));
        assertEquals("TEAM_LEADER", AdminHierarchyService.levelOf("TEAM_LEADER", List.of("TEAM_LEADER_TCHAT", "AGENT_TCHAT")));
        assertEquals("AGENT", AdminHierarchyService.levelOf("AGENT", List.of("AGENT_INBOUND")));
        assertEquals("ADMIN", AdminHierarchyService.levelOf("ADMIN", List.of()));
        assertEquals("A_CLASSER", AdminHierarchyService.levelOf(null, List.of()));
        assertEquals("A_CLASSER", AdminHierarchyService.levelOf("EXCELLIAM", List.of()));
    }

    @Test
    void teamOfLeadersAndAgents() {
        assertEquals("TCHAT", AdminHierarchyService.teamOf("TEAM_LEADER", null, null, List.of("TEAM_LEADER_TCHAT")));
        assertEquals("OUTBOUND", AdminHierarchyService.teamOf("TEAM_LEADER", "outbound", null, List.of()));
        assertNull(AdminHierarchyService.teamOf("TEAM_LEADER", null, null, List.of()));
        assertEquals("RAFIKI", AdminHierarchyService.teamOf("AGENT", null, "INBOUND MAIL", List.of("AGENT_RAFIKI")));
        assertEquals("INBOUND_VOICE", AdminHierarchyService.teamOf("AGENT", null, null, List.of("AGENT_INBOUND")));
        assertNull(AdminHierarchyService.teamOf("RH", null, null, List.of("RH")));
    }

    private static AdminHierarchyService.Person p(long id, String name, String level, String team, boolean active) {
        List<String> codes = level.equals("TEAM_LEADER") && team != null ? List.of("TEAM_LEADER_" + team) : List.of();
        return new AdminHierarchyService.Person(id, "u" + id, name, null, "CI", active, level, null, team, null, List.of(), List.of(),
                AdminHierarchyService.warningsOf(level, team, active, List.of(), codes), "CI", null,
                AdminHierarchyService.leaderSourceOf(level, List.of(), codes));
    }

    @Test
    void agentsSitUnderTheirTeamLeader() {
        List<AdminHierarchyService.Person> people = new ArrayList<>(List.of(
                p(1, "Sup", "SUPERVISEUR", null, true), p(2, "Inès", "TEAM_LEADER", "TCHAT", true), p(3, "Awa", "AGENT", "TCHAT", true),
                p(4, "Noël", "TEAM_LEADER", null, true), p(5, "Koffi", "AGENT", null, true), p(6, "Zoé", "AGENT", "TCHAT", false)));
        AdminHierarchyService.Hierarchy h = AdminHierarchyService.build(people);
        assertEquals(1, h.supervisors().size());
        AdminHierarchyService.TeamBlock tchat = h.teams().stream().filter(t -> t.code().equals("TCHAT")).findFirst().orElseThrow();
        assertEquals(List.of("Inès"), tchat.leaders().stream().map(AdminHierarchyService.Person::name).toList());
        assertEquals(List.of("Awa", "Zoé"), tchat.agents().stream().map(AdminHierarchyService.Person::name).toList(), "actifs d'abord");
        AdminHierarchyService.TeamBlock none = h.teams().get(h.teams().size() - 1);
        assertEquals("SANS_EQUIPE", none.code());
        assertEquals(1, none.leaders().size());
        assertEquals(1, none.agents().size());
        assertEquals(2, h.counts().get("WARNINGS"), "Team Leader et agent sans équipe (le compte désactivé n'est pas compté)");
    }

    @Test
    void leaderOnlyByLedTeamIsFlagged() {
        var agentRole = List.of(new AdminHierarchyService.Item(1L, "Agent Inbound", "Agent Inbound"));
        var tlRole = List.of(new AdminHierarchyService.Item(2L, "Team Leader Inbound Mail", "Team Leader Inbound Mail"));
        assertEquals("LED_TEAM", AdminHierarchyService.leaderSourceOf("TEAM_LEADER", agentRole, List.of("AGENT_INBOUND_MAIL")));
        assertEquals("ROLE", AdminHierarchyService.leaderSourceOf("TEAM_LEADER", tlRole, List.of()));
        assertEquals("SERVICE", AdminHierarchyService.leaderSourceOf("TEAM_LEADER", agentRole, List.of("TEAM_LEADER_TCHAT")));
        assertNull(AdminHierarchyService.leaderSourceOf("AGENT", agentRole, List.of()));
        assertTrue(AdminHierarchyService.warningsOf("TEAM_LEADER", "INBOUND_MAIL", true, agentRole, List.of("AGENT_INBOUND_MAIL"))
                .stream().anyMatch(w -> w.contains("équipe menée")));
    }
}

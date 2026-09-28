package com.ecobank.rccportal.service;

import com.ecobank.rccportal.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** MON RCC n'affiche plus la même chose partout : portail, couleur et correspondants propres à chacun. */
class MonRccSpaceServiceTest {

    private static MonRccSpaceService.Member m(long id, String name, String team, String ledTeam, String... services) {
        return new MonRccSpaceService.Member(id, "u" + id, name, team, Set.of(services), ledTeam, true, "CIV");
    }

    private final List<MonRccSpaceService.Member> all = List.of(
            m(1, "Awa Tchat", "TCHAT", null, "AGENT_TCHAT"),
            m(2, "Marc Tchat", "TCHAT", null, "AGENT_TCHAT"),
            m(3, "Haoua Rafiki", "RAFIKI", null, "AGENT_RAFIKI"),
            m(4, "Yao Voix", "INBOUND_VOICE", null, "AGENT_INBOUND"),
            m(5, "Fatou TL Mail", null, "INBOUND_MAIL", "TEAM_LEADER_INBOUND_MAIL"),
            m(6, "Serge TL Voix", null, "INBOUND_VOICE", "TEAM_LEADER_INBOUND_VOICE"),
            m(7, "Nadia QA", null, null, "QUALITY_ASSURANCE"),
            m(8, "Paul RH", null, null, "RH"),
            m(9, "Eric Superviseur", null, null, "SUPERVISEUR"));

    private static List<String> names(List<MonRccSpaceService.Contact> c) {
        return c.stream().map(MonRccSpaceService.Contact::name).toList();
    }

    @Test
    void aChatAgentSeesHisTeamLeaderAndChatColleagues() {
        var c = MonRccSpaceService.contacts("AGENT", all.get(0), all);
        assertEquals("Fatou TL Mail", c.get(0).name());                     // son Team Leader d'abord
        assertEquals("Mon Team Leader", c.get(0).group());
        assertTrue(names(c).contains("Marc Tchat"));
        assertFalse(names(c).contains("Haoua Rafiki"));                     // pas les agents d'un autre canal
        assertFalse(names(c).contains("Serge TL Voix"));
    }

    @Test
    void aTeamLeaderSeesHisWholeTeamIncludingChatAndRafiki() {
        var c = MonRccSpaceService.contacts("TEAM_LEADER", all.get(4), all);
        assertTrue(names(c).containsAll(List.of("Awa Tchat", "Marc Tchat", "Haoua Rafiki")));
        assertFalse(c.stream().anyMatch(x -> x.name().equals("Yao Voix")));
        assertEquals("Mon équipe", c.get(0).group());
    }

    @Test
    void chatTeamLeaderPortalIsLimitedToTheChannel() {
        var withChatTl = new ArrayList<>(all);
        withChatTl.add(m(11, "Ines TL Tchat", null, "TCHAT", "TEAM_LEADER_TCHAT"));
        var tl = MonRccSpaceService.contacts("TEAM_LEADER", withChatTl.get(9), withChatTl);
        assertTrue(names(tl).containsAll(List.of("Awa Tchat", "Marc Tchat")));
        assertFalse(names(tl).contains("Haoua Rafiki"));                   // le Rafiki a son propre Team Leader
        var agent = MonRccSpaceService.contacts("AGENT", all.get(0), withChatTl);
        assertEquals("Ines TL Tchat", agent.get(0).name());                // son Team Leader de canal, pas celui du pôle
        assertEquals(1, agent.stream().filter(c -> c.group().equals("Mon Team Leader")).count());
    }

    @Test
    void rhAndSupervisorGetTheirOwnCorrespondents() {
        assertEquals(List.of("Équipe RH", "Superviseur", "Team Leaders"),
                MonRccSpaceService.contacts("RH", m(10, "Autre RH", null, null, "RH"), all).stream().map(MonRccSpaceService.Contact::group).distinct().toList());
        assertEquals("Team Leaders", MonRccSpaceService.contacts("SUPERVISOR", all.get(8), all).get(0).group());
    }

    @Test
    void eachPortalHasItsOwnLook() {
        assertEquals("tchat", MonRccSpaceService.theme("AGENT", "/portail-tchat"));
        assertEquals("rafiki", MonRccSpaceService.theme("AGENT", "/portail-rafiki"));
        assertEquals("tl", MonRccSpaceService.theme("TEAM_LEADER", "/team-leader"));
        assertEquals("QA_SUPERVISOR", MonRccSpaceService.profile(new AuthenticatedUser("h", "QA", "SUPERVISEUR_QA", null)));
        assertEquals("RAFIKI", MonRccSpaceService.teamOf("INBOUND MAIL", Set.of(), "RAFIKI"));   // placement RH prioritaire
        assertEquals("TCHAT", MonRccSpaceService.teamOf("INBOUND TCHAT", Set.of(), null));
        assertEquals("/portail-rafiki", UserService.digitalChannelPortal("INBOUND RAFIKI"));
        assertEquals("/portail-tchat", UserService.digitalChannelPortal("Live Chat"));
        assertNull(UserService.digitalChannelPortal("INBOUND MAIL"));
    }
}

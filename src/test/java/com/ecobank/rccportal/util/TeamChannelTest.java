package com.ecobank.rccportal.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Team Leaders Tchat et Rafiki : même portail, restreint à leur canal ; l'Inbound Mail garde tout le pôle. */
class TeamChannelTest {

    @Test
    void channelLeadersOnlyManageTheirChannel() {
        assertTrue(TeamClassifier.belongsTo("TCHAT", "INBOUND TCHAT", null));
        assertFalse(TeamClassifier.belongsTo("TCHAT", "INBOUND RAFIKI", null));
        assertFalse(TeamClassifier.belongsTo("TCHAT", "INBOUND MAIL", null));
        assertTrue(TeamClassifier.belongsTo("RAFIKI", "Inbound Mail", Set.of("AGENT_RAFIKI")));
        assertTrue(TeamClassifier.belongsTo("INBOUND_MAIL", "INBOUND TCHAT", null));   // le pôle garde Tchat et Rafiki
        assertFalse(TeamClassifier.belongsTo("INBOUND_VOICE", "INBOUND TCHAT", null));
        assertEquals(TeamClassifier.Team.INBOUND_MAIL, TeamClassifier.teamOf("RAFIKI"));
    }

    record L(String name, String led) {}

    @Test
    void leaveAndSwapsGoToTheChannelLeaderFirst() {
        List<L> leaders = List.of(new L("mail", "INBOUND_MAIL"), new L("tchat", "TCHAT"), new L("voix", "INBOUND_VOICE"));
        assertEquals("tchat", TeamClassifier.leaderFor("INBOUND TCHAT", leaders, L::led).name());
        assertEquals("mail", TeamClassifier.leaderFor("INBOUND RAFIKI", leaders, L::led).name());   // pas de TL Rafiki : celui du pôle
        assertEquals("voix", TeamClassifier.leaderFor("INBOUND VOICE", leaders, L::led).name());
        assertTrue(TeamClassifier.matchesTeam("INBOUND_VOICE", "INBOUND VOICE"));                 // code d'équipe ↔ activité libre
        assertTrue(TeamClassifier.matchesTeam("TCHAT", "Live Chat"));
    }
}

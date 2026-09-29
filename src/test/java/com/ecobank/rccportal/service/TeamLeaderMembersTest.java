package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Le Team Leader ajoute et retire les membres de SON équipe (Tchat compris) ; une sortie est tracée au RH. */
class TeamLeaderMembersTest {

    private final UserRepository users = mock(UserRepository.class);
    private final HrOrganizationService hr = mock(HrOrganizationService.class);
    private final TeamLeaderService svc = new TeamLeaderService(users, null, null, null, null, mock(com.ecobank.rccportal.repository.UserRoleRepository.class));
    private final AuthenticatedUser tl = new AuthenticatedUser("ines", "TEAM_LEADER", "TEAM_LEADER_TCHAT", "Inès");
    private final User amy = User.builder().id(5L).username("amy").name("Amy").activity("INBOUND TCHAT").accountEnabled(true).build();
    private final User haoua = User.builder().id(6L).username("haoua").name("Haoua").activity("INBOUND RAFIKI").accountEnabled(true).build();

    TeamLeaderMembersTest() {
        svc.setHrOrganization(hr);
        when(users.findFirstByUsernameIgnoreCase("ines")).thenReturn(Optional.of(User.builder().id(1L).username("ines").ledTeam("TCHAT").build()));
        when(users.findById(5L)).thenReturn(Optional.of(amy));
        when(users.findById(6L)).thenReturn(Optional.of(haoua));
        when(users.findAll()).thenReturn(List.of(amy, haoua));
    }

    @Test
    void transferKeepsTheAccountActive() {
        svc.removeMember(tl, 5L, "TRANSFER", null, null);
        assertNull(amy.getActivity());
        assertTrue(amy.getAccountEnabled());
        verify(hr, never()).recordDepartureForTeamLeader(anyString(), any(), anyString(), anyString());
    }

    @Test
    void departureIsRecordedForHrAndDisablesTheAccount() {
        svc.removeMember(tl, 5L, "DEPARTURE", "FIN_CONTRAT", "Fin de mission");
        verify(hr).recordDepartureForTeamLeader(eq("ines"), eq(5L), eq("FIN_CONTRAT"), contains("Fin de mission"));
        assertFalse(amy.getAccountEnabled());
        assertThrows(ApiException.class, () -> svc.removeMember(tl, 5L, "DEPARTURE", null, null));   // motif obligatoire
    }

    @Test
    void onlyAgentsOfMyChannelCanBeRemovedAndAddedAgentsJoinIt() {
        assertThrows(ApiException.class, () -> svc.removeMember(tl, 6L, "TRANSFER", null, null));   // agent Rafiki
        svc.addMember(tl, 6L);
        assertEquals("INBOUND TCHAT", haoua.getActivity());
    }

    private static String contains(String s) {
        return org.mockito.ArgumentMatchers.contains(s);
    }
}

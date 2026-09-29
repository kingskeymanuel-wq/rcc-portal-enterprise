package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Recherche élargie (n'importe qui) et ajout synchronisé : équipe, service agent, notifications. */
class TeamLeaderAddAnyoneTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserRoleRepository roles = mock(UserRoleRepository.class);
    private final UserServiceAssignmentRepository assignments = mock(UserServiceAssignmentRepository.class);
    private final RccServiceRepository services = mock(RccServiceRepository.class);
    private final RccNotificationRepository notifs = mock(RccNotificationRepository.class);
    private final TeamLeaderService svc = new TeamLeaderService(users, null, null, null, null, roles);
    private final AuthenticatedUser tl = new AuthenticatedUser("ines", "TEAM_LEADER", "TEAM_LEADER_TCHAT", "Inès Touré");

    private final User ines = User.builder().id(1L).username("ines").name("Inès Touré").ledTeam("TCHAT").accountEnabled(true).build();
    private final User noel = User.builder().id(2L).username("noel").name("Monsieur Noël").ledTeam("OUTBOUND").accountEnabled(true).build();
    private final User koffi = User.builder().id(3L).username("koffi").name("Koffi Yao").activity("OUTBOUND").accountEnabled(true).build();
    private final User awa = User.builder().id(4L).username("awa").name("Awa Sans Équipe").accountEnabled(true).build();
    private final User gone = User.builder().id(5L).username("gone").name("Parti").accountEnabled(false).build();
    private final RccService agentOutbound = svc("AGENT_OUTBOUND"), agentTchat = svc("AGENT_TCHAT"), tlOutbound = svc("TEAM_LEADER_OUTBOUND"), qa = svc("QUALITY_ASSURANCE");
    private final List<UserServiceAssignment> koffiServices = new ArrayList<>();

    private static RccService svc(String code) {
        RccService s = new RccService();
        s.setId((long) code.hashCode());
        s.setCode(code);
        s.setName(code);
        return s;
    }

    private UserServiceAssignment assign(User u, RccService s) {
        return UserServiceAssignment.builder().user(u).service(s).build();
    }

    private UserRole role(User u, String name) {
        Role r = new Role();
        r.setName(name);
        return UserRole.builder().user(u).role(r).build();
    }

    TeamLeaderAddAnyoneTest() {
        svc.setSync(assignments, services, notifs);
        koffiServices.add(assign(koffi, agentOutbound));
        koffiServices.add(assign(koffi, qa));
        when(users.findFirstByUsernameIgnoreCase("ines")).thenReturn(Optional.of(ines));
        when(users.findAll()).thenReturn(List.of(ines, noel, koffi, awa, gone));
        for (User u : List.of(ines, noel, koffi, awa, gone)) when(users.findById(u.getId())).thenReturn(Optional.of(u));
        when(assignments.findAll()).thenReturn(List.of(koffiServices.get(0), koffiServices.get(1), assign(noel, tlOutbound)));
        when(assignments.findServicesByUserId(any())).thenReturn(List.of());
        when(assignments.findServicesByUserId(3L)).thenAnswer(i -> new ArrayList<>(koffiServices));
        when(assignments.findServicesByUserId(2L)).thenReturn(List.of(assign(noel, tlOutbound)));
        when(assignments.findByUserId(3L)).thenAnswer(i -> new ArrayList<>(koffiServices));
        when(roles.findAll()).thenReturn(List.of(role(koffi, "Agent Outbound"), role(noel, "Team Leader Outbound"), role(awa, "agent")));
        when(roles.findRolesByUserId(any())).thenReturn(List.of());
        when(roles.findRolesByUserId(3L)).thenReturn(List.of(role(koffi, "Agent Outbound")));
        when(roles.findRolesByUserId(2L)).thenReturn(List.of(role(noel, "Team Leader Outbound")));
        when(services.findByCodeIgnoreCase("AGENT_TCHAT")).thenReturn(Optional.of(agentTchat));
    }

    @Test
    void searchShowsEveryoneWithTheirTeamAndWhyTheyCannotBeAdded() {
        List<TeamLeaderService.Candidate> all = svc.searchCandidates(tl, "");
        assertEquals(4, all.size(), "tout le monde sauf moi");
        var k = all.stream().filter(c -> c.username().equals("koffi")).findFirst().orElseThrow();
        assertTrue(k.addable(), "un « Agent Outbound » n'est plus pris pour un compte de management");
        assertEquals("OUTBOUND", k.team());
        assertEquals("Monsieur Noël", k.currentLeader());
        var n = all.stream().filter(c -> c.username().equals("noel")).findFirst().orElseThrow();
        assertFalse(n.addable());
        assertTrue(n.reason().contains("Team Leader"));
        assertFalse(all.stream().filter(c -> c.username().equals("gone")).findFirst().orElseThrow().addable());
        assertEquals(List.of("koffi"), svc.searchCandidates(tl, "outbound koffi").stream().map(TeamLeaderService.Candidate::username).toList());
        assertEquals(List.of("awa", "gone"), svc.searchCandidates(tl, "sans equipe").stream().map(TeamLeaderService.Candidate::username).toList());
    }

    @Test
    void transferSynchronisesTeamServiceAndNotifies() {
        svc.addMember(tl, 3L);
        assertEquals("INBOUND TCHAT", koffi.getActivity());
        verify(assignments).delete(koffiServices.get(0));           // ancien service agent retiré
        verify(assignments, never()).delete(koffiServices.get(1));  // service QA conservé
        ArgumentCaptor<UserServiceAssignment> added = ArgumentCaptor.forClass(UserServiceAssignment.class);
        verify(assignments).save(added.capture());
        assertEquals("AGENT_TCHAT", added.getValue().getService().getCode());
        ArgumentCaptor<RccNotification> n = ArgumentCaptor.forClass(RccNotification.class);
        verify(notifs, times(2)).save(n.capture());
        assertEquals(Set.of("koffi", "noel"), new HashSet<>(n.getAllValues().stream().map(x -> x.getTargetUser().getUsername()).toList()));
    }

    @Test
    void managementAndDisabledAccountsAreRefused() {
        assertThrows(ApiException.class, () -> svc.addMember(tl, 2L));
        assertThrows(ApiException.class, () -> svc.addMember(tl, 5L));
    }
}

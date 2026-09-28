package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PlanifyShiftsRequest;
import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Planning créé par un Team Leader : publication directe OU envoi (facultatif) à Excelliam. */
class TeamLeaderPlanningTest {

    private final AgentScheduleRepository schedules = mock(AgentScheduleRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserServiceAssignmentRepository services = mock(UserServiceAssignmentRepository.class);
    private final UserRoleRepository roles = mock(UserRoleRepository.class);
    private final RccNotificationRepository notifs = mock(RccNotificationRepository.class);
    private final ScheduleService svc = new ScheduleService(schedules, mock(ShiftEventRepository.class), users, services,
            mock(RccServiceRepository.class), null, null, roles, notifs);
    private final List<AgentSchedule> saved = new ArrayList<>();
    private static final AuthenticatedUser TL = new AuthenticatedUser("tl", "TEAM_LEADER", "TEAM_LEADER_INBOUND_VOICE", "Awa Koné");

    TeamLeaderPlanningTest() {
        User tl = user(1L, "tl", null); tl.setLedTeam("INBOUND_VOICE"); tl.setName("Awa Koné");
        user(2L, "yao", "INBOUND VOICE");            // Activité écrite librement : fait bien partie de l'équipe
        user(3L, "zie", null);                        // Activité vide, service Agent Inbound → Inbound Voix
        user(4L, "ama", "OUTBOUND");                  // autre équipe
        RccService inbound = new RccService(); inbound.setCode("AGENT_INBOUND");
        UserServiceAssignment a = new UserServiceAssignment(); a.setService(inbound);
        when(services.findServicesByUserId(anyLong())).thenReturn(List.of());
        when(services.findServicesByUserId(3L)).thenReturn(List.of(a));
        when(schedules.findByUserAndWorkDate(any(), any())).thenReturn(Optional.empty());
        when(schedules.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
        when(roles.findByRoleNameIgnoreCase("EXCELLIAM")).thenReturn(List.of());
    }

    private User user(long id, String username, String activity) {
        User u = new User(); u.setId(id); u.setUsername(username); u.setActivity(activity);
        when(users.findFirstByUsernameIgnoreCase(username)).thenReturn(Optional.of(u));
        return u;
    }

    private PlanifyShiftsRequest request(String... usernames) {
        List<PlanifyShiftsRequest.AgentShiftAssignment> list = new ArrayList<>();
        for (String u : usernames) list.add(new PlanifyShiftsRequest.AgentShiftAssignment(u, "M"));
        return new PlanifyShiftsRequest(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), list);
    }

    @Test
    void directPublicationIsLiveImmediatelyAndAgentsAreNotified() {
        var r = svc.submitTeamPlanning(TL, request("yao", "zie"));
        assertEquals(2, r.agentsPlanified());
        assertEquals(6, r.entriesCreated());
        assertTrue(saved.stream().allMatch(s -> "APPROVED".equals(s.getApprovalStatus()) && "TEAM_LEADER".equals(s.getOrigin())));
        ArgumentCaptor<RccNotification> n = ArgumentCaptor.forClass(RccNotification.class);
        verify(notifs, times(2)).save(n.capture());
        assertTrue(n.getAllValues().stream().allMatch(x -> x.getContent().contains("publié par Awa Koné")));
        verify(roles, never()).findByRoleNameIgnoreCase("EXCELLIAM");   // Excelliam n'est pas sollicité
    }

    /** Plannings envoyés à Excelliam avant la suppression de son portail : le Team Leader les publie lui-même. */
    @Test
    void plansLeftWaitingForExcelliamArePublishedByTheTeamLeader() {
        User yao = users.findFirstByUsernameIgnoreCase("yao").orElseThrow();
        AgentSchedule pending = AgentSchedule.builder().user(yao).workDate(LocalDate.of(2026, 10, 1)).build();
        pending.setApprovalStatus("PENDING");
        pending.setOrigin("TEAM_LEADER");
        when(schedules.findByWorkDateBetween(any(), any())).thenReturn(List.of(pending));
        assertEquals(1, svc.publishTeamPlanning(TL, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
        assertEquals("APPROVED", pending.getApprovalStatus());
    }

    @Test
    void agentsOfAnotherTeamAreRefused() {
        ApiException e = assertThrows(ApiException.class, () -> svc.submitTeamPlanning(TL, request("ama")));
        assertTrue(e.getMessage().contains("n'appartient pas à votre équipe"));
    }
}

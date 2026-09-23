package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** La permutation de shift concerne uniquement les agents ; le Team Leader voit et décide. */
class ShiftSwapAgentOnlyTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 24);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 25);

    private ShiftSwapRequestRepository swaps;
    private AgentScheduleRepository schedules;
    private UserRepository users;
    private UserRoleRepository roles;
    private ShiftSwapService service;
    private long ids = 1;

    @BeforeEach
    void setUp() {
        swaps = mock(ShiftSwapRequestRepository.class);
        schedules = mock(AgentScheduleRepository.class);
        users = mock(UserRepository.class);
        roles = mock(UserRoleRepository.class);
        when(swaps.save(any())).thenAnswer(i -> i.getArgument(0));
        when(schedules.findByUserAndWorkDate(any(), any())).thenAnswer(i -> Optional.of(AgentSchedule.builder()
                .user(i.getArgument(0)).workDate(i.getArgument(1)).shiftCode("M2")
                .plannedStartTime(LocalTime.of(8, 0)).plannedEndTime(LocalTime.of(17, 0)).approvalStatus("APPROVED").build()));
        service = new ShiftSwapService(swaps, schedules, users, roles, mock(RccNotificationRepository.class));
    }

    private User user(String username, String role, String ledTeam) {
        User u = new User();
        u.setId(ids++);
        u.setUsername(username);
        u.setName(username);
        u.setActivity("INBOUND_VOICE");
        u.setLedTeam(ledTeam);
        when(users.findFirstByUsernameIgnoreCase(username)).thenReturn(Optional.of(u));
        when(roles.findRolesByUserId(u.getId())).thenReturn(List.of(
                UserRole.builder().user(u).role(Role.builder().name(role).build()).build()));
        return u;
    }

    @Test
    void agentsCanProposeASwap() {
        user("awa", "AGENT", null);
        user("moussa", "AGENT", null);
        var r = service.submit("awa", "AGENT", D1, "moussa", D2, null);
        assertEquals("PENDING", r.peerStatus());
    }

    @Test
    void teamLeaderCannotRequestASwap() {
        user("tl", "TEAM_LEADER", "INBOUND_VOICE");
        user("moussa", "AGENT", null);
        assertThrows(ApiException.class, () -> service.submit("tl", "TEAM_LEADER", D1, "moussa", D2, null));
    }

    @Test
    void cannotTargetANonAgent() {
        user("awa", "AGENT", null);
        user("rh", "RH", null);
        assertThrows(ApiException.class, () -> service.submit("awa", "AGENT", D1, "rh", D2, null));
    }

    @Test
    void teamLeaderSeesEveryStatusOfHisTeamOnly() {
        User tl = user("tl", "TEAM_LEADER", "INBOUND_VOICE");
        User a = user("awa", "AGENT", null);
        User b = user("moussa", "AGENT", null);
        User other = user("autre", "AGENT", null);
        other.setActivity("OUTBOUND");
        ShiftSwapRequest pending = ShiftSwapRequest.builder().requester(a).targetUser(b).requesterDate(D1).targetDate(D2)
                .peerStatus("ACCEPTED").teamLeaderStatus("PENDING").build();
        ShiftSwapRequest waitingPeer = ShiftSwapRequest.builder().requester(b).targetUser(a).requesterDate(D1).targetDate(D2)
                .peerStatus("PENDING").teamLeaderStatus("NOT_SUBMITTED").build();
        ShiftSwapRequest approved = ShiftSwapRequest.builder().requester(a).targetUser(b).requesterDate(D1).targetDate(D2)
                .peerStatus("ACCEPTED").teamLeaderStatus("APPROVED").build();
        ShiftSwapRequest otherTeam = ShiftSwapRequest.builder().requester(other).targetUser(other).requesterDate(D1).targetDate(D2)
                .peerStatus("ACCEPTED").teamLeaderStatus("PENDING").build();
        when(swaps.findAll(any(Sort.class))).thenReturn(List.of(pending, waitingPeer, approved, otherTeam));

        var list = service.listForTeamLeader(tl.getUsername());
        assertEquals(3, list.size());
        assertTrue(list.stream().allMatch(s -> "INBOUND_VOICE".equals(s.team())));
    }
}

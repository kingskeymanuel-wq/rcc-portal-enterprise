package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.UserDirectoryResponse;
import com.ecobank.rccportal.model.AssignedTask;
import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AssignedTaskRepository;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoachingMeetingServiceTest {

    private static final AuthenticatedUser TL = new AuthenticatedUser("tl.kone", "TEAM_LEADER", "TEAM_LEADER_INBOUND", "Awa Koné");
    private static final AuthenticatedUser AGENT = new AuthenticatedUser("agent.yao", "AGENT", "INBOUND", "Yao Kouassi");
    private static final AuthenticatedUser OTHER = new AuthenticatedUser("agent.zie", "AGENT", "INBOUND", "Zié Traoré");
    private static final AuthenticatedUser HEAD_QA = new AuthenticatedUser("head.qa", "QA", "SUPERVISEUR_QA", "Head QA");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AssignedTaskRepository tasks = mock(AssignedTaskRepository.class);
    private final RccNotificationRepository notifs = mock(RccNotificationRepository.class);
    private final NotificationService mail = mock(NotificationService.class);
    private final TeamLeaderService teamLeaderService = mock(TeamLeaderService.class);
    private final CoachingMeetingService svc = new CoachingMeetingService(jdbc, users, tasks, notifs, mail, teamLeaderService);
    private final Map<Long, User> byId = new HashMap<>();

    CoachingMeetingServiceTest() {
        user(1L, "tl.kone"); user(2L, "agent.yao"); user(3L, "agent.zie"); user(9L, "head.qa"); user(10L, "head.rcc");
        when(users.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(byId.get(i.<Long>getArgument(0))));
        when(users.findAllById(any())).thenAnswer(i -> {
            List<User> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) if (byId.containsKey(id)) out.add(byId.get(id));
            return out;
        });
    }

    private void user(long id, String username) {
        User u = new User(); u.setId(id); u.setUsername(username); u.setName(username);
        byId.put(id, u);
        when(users.findFirstByUsernameIgnoreCase(username)).thenReturn(Optional.of(u));
    }

    @SuppressWarnings("unchecked")
    private void meetingRow(String status) {
        CoachingMeetingService.Row row = new CoachingMeetingService.Row(5L, 1L, 2L, LocalDateTime.of(2026, 9, 26, 10, 0), 30, "Salle",
                "Améliorer le score QA", null, List.of(), status, "Score QA à 62 %", null, "Reformulation", "Double écoute", null,
                null, null, null, null);
        when(jdbc.query(startsWith("SELECT * FROM dbo.CoachingMeetings WHERE"), any(RowMapper.class), eq(5L))).thenReturn(List.of(row));
    }

    private static UserDirectoryResponse member(String username) {
        return new UserDirectoryResponse(2, username, username, null, "AGENT", "INBOUND", null, true, null, null);
    }

    @Test
    void onlyTeamLeadersCanSchedule() {
        var req = new CoachingMeetingService.ScheduleRequest("agent.zie", LocalDateTime.now().plusDays(1), 30, null, "QA", null, null);
        ApiException e = assertThrows(ApiException.class, () -> svc.schedule(AGENT, req));
        assertTrue(e.getMessage().contains("Team Leaders"));
    }

    @Test
    void teamLeaderCannotMeetAnAgentOutsideTheTeam() {
        when(teamLeaderService.teamMembers(TL)).thenReturn(List.of(member("agent.yao")));
        var req = new CoachingMeetingService.ScheduleRequest("agent.zie", LocalDateTime.now().plusDays(1), 30, null, "QA", null, null);
        ApiException e = assertThrows(ApiException.class, () -> svc.schedule(TL, req));
        assertTrue(e.getMessage().contains("équipe"));
        verify(jdbc, never()).update(startsWith("INSERT INTO dbo.CoachingMeetings"), any(Object[].class));
    }

    @Test
    void reportRequiresReasonsImprovementsAndPlan() {
        meetingRow(CoachingMeetingService.PLANNED);
        var incomplete = new CoachingMeetingService.ReportRequest("Score QA bas", null, null, "Double écoute", null);
        ApiException e = assertThrows(ApiException.class, () -> svc.report(TL, 5L, incomplete));
        assertTrue(e.getMessage().contains("Axes d'amélioration"));
        assertThrows(ApiException.class, () -> svc.report(new AuthenticatedUser("agent.zie", "TEAM_LEADER", null, null), 5L,
                new CoachingMeetingService.ReportRequest("a", null, "b", "c", null)));  // pas le TL du meeting
    }

    @Test
    void onlyTheAgentApprovesAndMustTickTheBox() {
        meetingRow(CoachingMeetingService.REPORTED);
        assertThrows(ApiException.class, () -> svc.acknowledge(OTHER, 5L, new CoachingMeetingService.AckRequest(true, null)));
        assertThrows(ApiException.class, () -> svc.acknowledge(TL, 5L, new CoachingMeetingService.AckRequest(true, null)));
        ApiException e = assertThrows(ApiException.class, () -> svc.acknowledge(AGENT, 5L, new CoachingMeetingService.AckRequest(false, null)));
        assertTrue(e.getMessage().contains("lu et j'approuve"));
    }

    @Test
    void cannotApproveBeforeTheReport() {
        meetingRow(CoachingMeetingService.PLANNED);
        assertThrows(ApiException.class, () -> svc.acknowledge(AGENT, 5L, new CoachingMeetingService.AckRequest(true, null)));
    }

    @Test
    void approvalGoesUpToHeadQaHeadRccAndTheTeamLeader() {
        meetingRow(CoachingMeetingService.REPORTED);
        when(jdbc.queryForList(contains("SUPERVISEUR_QA"), eq(Long.class))).thenReturn(List.of(9L, 10L));
        AssignedTask ack = AssignedTask.builder().category("MEETING_ACK").status("OPEN").relatedMeetingId(5L).build();
        when(tasks.findByRelatedMeetingId(5L)).thenReturn(List.of(ack));

        try { svc.acknowledge(AGENT, 5L, new CoachingMeetingService.AckRequest(true, "Merci")); }
        catch (ApiException ignored) { /* relecture finale : la ligne mockée reste identique */ }

        verify(jdbc).update(startsWith("UPDATE dbo.CoachingMeetings SET AgentComment"), eq("Merci"), eq(CoachingMeetingService.APPROVED), eq(5L));
        assertEquals("DONE", ack.getStatus());
        ArgumentCaptor<RccNotification> sent = ArgumentCaptor.forClass(RccNotification.class);
        verify(notifs, atLeastOnce()).save(sent.capture());
        Set<String> to = new HashSet<>();
        sent.getAllValues().forEach(n -> to.add(n.getTargetUser().getUsername()));
        assertEquals(Set.of("head.qa", "head.rcc", "tl.kone"), to);
        assertTrue(sent.getAllValues().stream().allMatch(n -> "OPEN_MEETING".equals(n.getActionType()) && "5".equals(n.getActionTarget())));
    }

    @Test
    void headQaSeesEveryMeetingOthersOnlyTheirs() {
        assertTrue(CoachingMeetingService.isHead(HEAD_QA));
        assertTrue(CoachingMeetingService.isHead(new AuthenticatedUser("sup", "SUPERVISOR", "SUPERVISEUR", null)));
        assertFalse(CoachingMeetingService.isHead(TL));
        meetingRow(CoachingMeetingService.PLANNED);
        assertThrows(ApiException.class, () -> svc.get(OTHER, 5L));
        assertEquals(5L, svc.get(HEAD_QA, 5L).id());
        var forAgent = svc.get(AGENT, 5L);
        assertFalse(forAgent.canReport());
        assertTrue(svc.get(TL, 5L).canReport());
    }
}

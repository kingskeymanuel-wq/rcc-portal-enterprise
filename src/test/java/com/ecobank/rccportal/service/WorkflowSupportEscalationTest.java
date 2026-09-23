package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CreateWorkflowRequestRequest;
import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Demandes d'aide agent : routage au Team Leader, prise en charge, escalade automatique à 72 h. */
class WorkflowSupportEscalationTest {

    private WorkflowRequestRepository requestRepository;
    private UserRepository userRepository;
    private RccNotificationRepository notificationRepository;
    private UserRoleRepository userRoleRepository;
    private UserServiceAssignmentRepository assignmentRepository;
    private WorkflowService service;
    private User agent, teamLeader, supervisor;

    @BeforeEach
    void setUp() {
        requestRepository = mock(WorkflowRequestRepository.class);
        userRepository = mock(UserRepository.class);
        notificationRepository = mock(RccNotificationRepository.class);
        userRoleRepository = mock(UserRoleRepository.class);
        assignmentRepository = mock(UserServiceAssignmentRepository.class);
        SiteSettingService settings = mock(SiteSettingService.class);
        service = new WorkflowService(requestRepository, userRepository, notificationRepository,
                mock(RccServiceRepository.class), assignmentRepository, settings,
                mock(SlaTargetRepository.class), userRoleRepository);

        agent = new User(); agent.setId(1L); agent.setUsername("awa"); agent.setName("Awa"); agent.setActivity("INBOUND_VOICE");
        teamLeader = new User(); teamLeader.setId(2L); teamLeader.setUsername("serge"); teamLeader.setName("Serge"); teamLeader.setLedTeam("INBOUND_VOICE");
        supervisor = new User(); supervisor.setId(3L); supervisor.setUsername("sup"); supervisor.setName("Superviseur");
        when(userRepository.findFirstByUsernameIgnoreCase("awa")).thenReturn(Optional.of(agent));
        when(userRepository.findFirstByUsernameIgnoreCase("serge")).thenReturn(Optional.of(teamLeader));
        when(userRepository.findFirstByUsernameIgnoreCase("sup")).thenReturn(Optional.of(supervisor));
        UserRole tlRole = mock(UserRole.class); when(tlRole.getUser()).thenReturn(teamLeader);
        UserRole supRole = mock(UserRole.class); when(supRole.getUser()).thenReturn(supervisor);
        when(userRoleRepository.findByRoleNameIgnoreCase("TEAM_LEADER")).thenReturn(List.of(tlRole));
        when(userRoleRepository.findByRoleNameIgnoreCase("SUPERVISOR")).thenReturn(List.of(supRole));
        when(assignmentRepository.findAll()).thenReturn(List.of());
        when(requestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void supportRequestGoesToTheAgentsOwnTeamLeader() {
        var res = service.submit("awa", new CreateWorkflowRequestRequest("EQUIPMENT", "Casque cassé", "Micro HS", "DAY",
                null, null, "ADMIN", "sup", null, "bloquant"));
        assertEquals("TEAM_LEADER", res.assignedTeam());
        assertEquals("serge", res.assignedToUsername());
        assertEquals("BLOQUANT", res.priority());
        verify(notificationRepository).save(argThat(n -> n.getTargetUser() == teamLeader));
    }

    @Test
    void withoutTeamLeaderTheRequestGoesStraightToSupervision() {
        agent.setActivity("CIB");
        var res = service.submit("awa", new CreateWorkflowRequestRequest("ACCESS", "Pas d'accès", null, "DAY",
                null, null, null, null, null, null));
        assertEquals("SUPERVISOR", res.assignedTeam());
        assertNotNull(res.escalatedAt());
        assertEquals("NORMAL", res.priority());
    }

    @Test
    void onlyOldOpenSupportRequestsAreEscalatedOnce() {
        WorkflowRequest old = WorkflowRequest.builder().requestId(10).type("TOOL").title("Softphone").status("PENDING")
                .requestedBy(agent).assignedTo(teamLeader).assignedTeam("TEAM_LEADER").build();
        old.setCreatedAt(LocalDateTime.now().minusHours(73));
        WorkflowRequest recent = WorkflowRequest.builder().requestId(11).type("ACCESS").title("Accès").status("PENDING")
                .requestedBy(agent).assignedTo(teamLeader).assignedTeam("TEAM_LEADER").build();
        recent.setCreatedAt(LocalDateTime.now().minusHours(10));
        when(requestRepository.findByStatusAndTypeIn(eq("PENDING"), any())).thenReturn(List.of(old, recent));

        assertEquals(1, service.escalateOverdueSupportRequests());
        assertNotNull(old.getEscalatedAt());
        assertNull(recent.getEscalatedAt());
        // superviseur + Team Leader + agent informés
        ArgumentCaptor<RccNotification> sent = ArgumentCaptor.forClass(RccNotification.class);
        verify(notificationRepository, times(3)).save(sent.capture());
        assertTrue(sent.getAllValues().stream().anyMatch(n -> n.getTargetUser() == supervisor && n.getContent().contains("Escalade")));

        assertEquals(0, service.escalateOverdueSupportRequests()); // pas deux fois
    }

    @Test
    void supervisorCanResolveOnlyAfterEscalation() {
        WorkflowRequest r = WorkflowRequest.builder().requestId(12).type("TOOL").title("CRM lent").status("PENDING")
                .requestedBy(agent).assignedTo(teamLeader).assignedTeam("TEAM_LEADER").build();
        r.setCreatedAt(LocalDateTime.now().minusHours(5));
        when(requestRepository.findById(12)).thenReturn(Optional.of(r));

        assertThrows(ApiException.class, () -> service.decide(12, "sup", "SUPERVISOR", true, "ok"));
        r.setEscalatedAt(LocalDateTime.now());
        var res = service.decide(12, "sup", "SUPERVISOR", true, "Poste remplacé");
        assertEquals("APPROVED", res.status());
    }

    @Test
    void teamLeaderAcknowledgesAndAgentIsInformed() {
        WorkflowRequest r = WorkflowRequest.builder().requestId(13).type("ACCESS").title("RCC360").status("PENDING")
                .requestedBy(agent).assignedTo(teamLeader).assignedTeam("TEAM_LEADER").build();
        r.setCreatedAt(LocalDateTime.now());
        when(requestRepository.findById(13)).thenReturn(Optional.of(r));

        var res = service.acknowledge(13, "serge", "TEAM_LEADER", "Ticket IT ouvert");
        assertNotNull(res.acknowledgedAt());
        assertEquals("Serge", res.acknowledgedBy());
        verify(notificationRepository).save(argThat(n -> n.getTargetUser() == agent && n.getContent().contains("prise en charge")));
        assertThrows(ApiException.class, () -> service.acknowledge(13, "awa", null, null));
    }
}

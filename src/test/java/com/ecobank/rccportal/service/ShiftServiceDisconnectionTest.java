package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.RecordShiftEventRequest;
import com.ecobank.rccportal.model.ShiftEvent;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.ShiftEventRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import com.ecobank.rccportal.repository.WorkflowRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Enregistrement de la déconnexion et reprise du minuteur — dépôts simulés en mémoire. */
class ShiftServiceDisconnectionTest {

    private final List<ShiftEvent> stored = new ArrayList<>();
    private ShiftService service;
    private User user;

    @BeforeEach
    void setUp() {
        ShiftEventRepository shiftRepo = mock(ShiftEventRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        UserServiceAssignmentRepository assignments = mock(UserServiceAssignmentRepository.class);
        user = new User();
        user.setUsername("agent.conseiller");
        when(userRepo.findFirstByUsernameIgnoreCase("agent.conseiller")).thenReturn(Optional.of(user));
        when(shiftRepo.save(any(ShiftEvent.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(shiftRepo.findByUserAndOccurredAtBetweenOrderByOccurredAtAsc(eq(user), any(), any()))
                .thenAnswer(inv -> new ArrayList<>(stored));
        when(assignments.findServicesByUserId(any())).thenReturn(List.of());
        service = new ShiftService(shiftRepo, userRepo, assignments,
                mock(WorkflowRequestRepository.class), mock(UserRoleRepository.class));
    }

    private void event(String type, LocalDateTime at) {
        stored.add(ShiftEvent.builder().user(user).eventType(type).occurredAt(at).build());
    }

    @Test
    void logoutRecordsTheDisconnectionTime() {
        event("LOGIN", LocalDateTime.now().minusHours(2));
        service.recordLogout("agent.conseiller");

        assertEquals("LOGOUT", stored.get(stored.size() - 1).getEventType());
        var status = service.getStatus("agent.conseiller");
        assertEquals("DISCONNECTED", status.currentState());
        assertNotNull(status.lastDisconnectedAt());
    }

    @Test
    void logoutIsNotRecordedWhenShiftNotStartedOrAlreadyEnded() {
        service.recordLogout("agent.conseiller");
        assertTrue(stored.isEmpty());

        event("LOGIN", LocalDateTime.now().minusHours(3));
        event("SHIFT_END", LocalDateTime.now().minusHours(1));
        service.recordLogout("agent.conseiller");
        assertEquals(2, stored.size());
    }

    @Test
    void reconnectionKeepsTheTimerRunningFromTheStartOfTheShiftAndReportsTheAbsence() {
        LocalDateTime start = LocalDateTime.now().minusHours(3).withNano(0);
        event("LOGIN", start);
        event("LOGOUT", start.plusHours(1));
        event("LOGIN", start.plusHours(1).plusMinutes(40));

        var status = service.getStatus("agent.conseiller");
        assertEquals("WORKING", status.currentState());
        assertEquals(start, status.currentStateSince());
        assertEquals(start.plusHours(1), status.lastDisconnectedAt());
        assertEquals(start.plusHours(1).plusMinutes(40), status.lastReconnectedAt());
        assertEquals(40, status.absenceMinutesToday());
        assertEquals(40, status.absenceMinutesInCurrentState());
        assertEquals(1, status.absences().size());
    }

    @Test
    void shiftActionFromAStillOpenTabAfterLogoutCountsAsAReconnection() {
        event("LOGIN", LocalDateTime.now().minusHours(2));
        event("LOGOUT", LocalDateTime.now().minusMinutes(30));

        var status = service.recordEvent("agent.conseiller", new RecordShiftEventRequest("PAUSE_START"));

        assertEquals("ON_PAUSE", status.currentState());
        List<String> types = stored.stream().map(ShiftEvent::getEventType).toList();
        assertEquals(List.of("LOGIN", "LOGOUT", "LOGIN", "PAUSE_START"), types);
    }
}

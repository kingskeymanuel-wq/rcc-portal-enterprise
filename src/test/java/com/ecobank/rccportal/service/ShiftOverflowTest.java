package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.LiveShiftStatusResponse;
import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.ShiftEvent;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.ShiftEventRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import com.ecobank.rccportal.repository.WorkflowRequestRepository;
import com.ecobank.rccportal.util.ShiftOverflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Débordement de shift : affichage « Débordement » après la fin prévue, clôture automatique à +1 h 30. */
class ShiftOverflowTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private final List<ShiftEvent> stored = new ArrayList<>();
    private ShiftService service;
    private User user;

    @BeforeEach
    void setUp() {
        ShiftEventRepository shiftRepo = mock(ShiftEventRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        UserServiceAssignmentRepository assignments = mock(UserServiceAssignmentRepository.class);
        user = new User();
        user.setId(7L);
        user.setUsername("agent.conseiller");
        when(userRepo.findFirstByUsernameIgnoreCase("agent.conseiller")).thenReturn(Optional.of(user));
        when(shiftRepo.save(any(ShiftEvent.class))).thenAnswer(inv -> { stored.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(shiftRepo.findByUserAndOccurredAtBetweenOrderByOccurredAtAsc(eq(user), any(), any())).thenAnswer(inv -> new ArrayList<>(stored));
        when(shiftRepo.findByOccurredAtBetweenOrderByUser_UsernameAscOccurredAtAsc(any(), any())).thenAnswer(inv -> new ArrayList<>(stored));
        when(assignments.findServicesByUserId(any())).thenReturn(List.of());
        service = new ShiftService(shiftRepo, userRepo, assignments, mock(WorkflowRequestRepository.class), mock(UserRoleRepository.class));
    }

    private void event(String type, LocalDateTime at) {
        stored.add(ShiftEvent.builder().user(user).eventType(type).occurredAt(at).build());
    }

    @Test
    void plannedEndComesFromThePlanningOrFromTheFirstLogin() {
        assertEquals(DAY.atTime(16, 0), ShiftOverflow.plannedEnd(DAY, LocalTime.of(8, 0), LocalTime.of(16, 0), null, 9));
        assertEquals(DAY.plusDays(1).atTime(6, 0), ShiftOverflow.plannedEnd(DAY, LocalTime.of(22, 0), LocalTime.of(6, 0), null, 9),
                "shift de nuit : fin le lendemain");
        assertEquals(DAY.atTime(17, 30), ShiftOverflow.plannedEnd(DAY, null, null, DAY.atTime(8, 30), 9), "sans planning : connexion + 9 h");
        assertNull(ShiftOverflow.plannedEnd(DAY, null, null, null, 9));
        assertEquals(DAY.atTime(17, 30), ShiftOverflow.autoCloseAt(DAY.atTime(16, 0)));
    }

    @Test
    void overflowOnlyWhileTheShiftIsStillOpen() {
        LocalDateTime end = DAY.atTime(16, 0);
        assertFalse(ShiftOverflow.overflowing("WORKING", end, DAY.atTime(15, 59)));
        assertTrue(ShiftOverflow.overflowing("WORKING", end, DAY.atTime(16, 20)));
        assertTrue(ShiftOverflow.overflowing("ON_PAUSE", end, DAY.atTime(16, 20)), "en pause après la fin : oubli aussi");
        assertFalse(ShiftOverflow.overflowing("SHIFT_ENDED", end, DAY.atTime(16, 20)));
        assertFalse(ShiftOverflow.overflowing("DISCONNECTED", end, DAY.atTime(16, 20)));
        assertEquals(20, ShiftOverflow.overflowMinutes("WORKING", end, DAY.atTime(16, 20)));
    }

    @Test
    void liveStatusShowsDebordementAfterThePlannedEnd() {
        AgentSchedule m = new AgentSchedule();
        m.setShiftCode("M");
        m.setWorkDate(DAY);
        m.setPlannedStartTime(LocalTime.of(8, 0));
        m.setPlannedEndTime(LocalTime.of(16, 0));
        var live = new LiveShiftStatusResponse("awa", "Awa", null, "WORKING", DAY.atTime(8, 2), null, null, 0, 0, DAY.atTime(16, 0));
        assertEquals("EN_POSTE", HrLiveService.statusOf(m, false, live, true, DAY, DAY.atTime(15, 0)));
        assertEquals("DEBORDEMENT", HrLiveService.statusOf(m, false, live, true, DAY, DAY.atTime(16, 30)));
    }

    @Test
    void forgottenShiftIsClosedOneHourThirtyAfterItsEnd() {
        LocalDateTime login = LocalDateTime.now().minusHours(11).truncatedTo(ChronoUnit.SECONDS); // fin prévue = login + 9 h, il y a 2 h
        event("LOGIN", login);
        assertEquals(1, service.autoCloseOverflows());
        ShiftEvent last = stored.get(stored.size() - 1);
        assertEquals("SHIFT_END", last.getEventType());
        assertEquals(login.plusHours(9).plusMinutes(90), last.getOccurredAt(), "le temps compté s'arrête à fin prévue + 1 h 30");
        assertEquals("SHIFT_ENDED", service.getStatus("agent.conseiller").currentState());
        assertEquals(0, service.autoCloseOverflows(), "déjà clôturé : rien de plus");
    }

    @Test
    void overflowingShiftStaysOpenUntilTheAutoCloseTime() {
        LocalDateTime login = LocalDateTime.now().minusHours(10).truncatedTo(ChronoUnit.SECONDS); // fin prévue il y a 1 h
        event("LOGIN", login);
        assertEquals(0, service.autoCloseOverflows());
        var status = service.getStatus("agent.conseiller");
        assertEquals("WORKING", status.currentState());
        assertEquals(login.plusHours(9), status.plannedEnd());
        assertEquals(login.plusHours(9).plusMinutes(90), status.autoCloseAt());
    }
}

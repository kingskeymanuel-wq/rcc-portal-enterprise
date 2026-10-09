package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.ShiftEvent;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AgentScheduleRepository;
import com.ecobank.rccportal.repository.ShiftEventRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.repository.UserServiceAssignmentRepository;
import com.ecobank.rccportal.repository.WorkflowRequestRepository;
import com.ecobank.rccportal.util.ShiftSession;
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

/** Shift calé sur le planning : la fin de shift (et non minuit) sépare deux shifts ; le suivant démarre à la connexion. */
class ShiftSessionTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 9);

    private final List<ShiftEvent> stored = new ArrayList<>();
    private final List<AgentSchedule> plan = new ArrayList<>();
    private ShiftService service;
    private User user;

    @BeforeEach
    void setUp() {
        ShiftEventRepository shiftRepo = mock(ShiftEventRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        UserServiceAssignmentRepository assignments = mock(UserServiceAssignmentRepository.class);
        AgentScheduleRepository schedules = mock(AgentScheduleRepository.class);
        user = new User();
        user.setId(7L);
        user.setUsername("agent.mail");
        when(userRepo.findFirstByUsernameIgnoreCase("agent.mail")).thenReturn(Optional.of(user));
        when(shiftRepo.save(any(ShiftEvent.class))).thenAnswer(inv -> { stored.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(shiftRepo.findByUserAndOccurredAtBetweenOrderByOccurredAtAsc(eq(user), any(), any())).thenAnswer(inv -> between(inv.getArgument(1), inv.getArgument(2)));
        when(shiftRepo.findByOccurredAtBetweenOrderByUser_UsernameAscOccurredAtAsc(any(), any())).thenAnswer(inv -> between(inv.getArgument(0), inv.getArgument(1)));
        when(schedules.findByUserAndWorkDate(eq(user), any())).thenAnswer(inv -> plan.stream()
                .filter(s -> s.getWorkDate().equals(inv.getArgument(1))).findFirst());
        when(schedules.findByWorkDate(any())).thenAnswer(inv -> plan.stream().filter(s -> s.getWorkDate().equals(inv.getArgument(0))).toList());
        when(assignments.findServicesByUserId(any())).thenReturn(List.of());
        service = new ShiftService(shiftRepo, userRepo, assignments, mock(WorkflowRequestRepository.class), mock(UserRoleRepository.class));
        service.setSchedules(schedules);
    }

    private List<ShiftEvent> between(LocalDateTime from, LocalDateTime to) {
        return stored.stream().filter(e -> !e.getOccurredAt().isBefore(from) && !e.getOccurredAt().isAfter(to)).toList();
    }

    private void event(String type, LocalDateTime at) {
        stored.add(ShiftEvent.builder().user(user).eventType(type).occurredAt(at).build());
    }

    private void planned(LocalDate day, String code, int start, int end) {
        AgentSchedule s = new AgentSchedule();
        s.setUser(user);
        s.setWorkDate(day);
        s.setShiftCode(code);
        s.setPlannedStartTime(LocalTime.of(start, 0));
        s.setPlannedEndTime(LocalTime.of(end, 0));
        plan.add(s);
    }

    @Test
    void currentShiftStartsAfterTheLastShiftEnd() {
        List<String> ev = List.of("LOGIN", "PAUSE_START", "PAUSE_END", "SHIFT_END", "LOGIN", "PAUSE_START");
        assertEquals(List.of("LOGIN", "PAUSE_START"), ShiftSession.current(ev, x -> x));
        List<String> ended = List.of("LOGIN", "SHIFT_END", "LOGIN", "LUNCH_START", "LUNCH_END", "SHIFT_END");
        assertEquals(List.of("LOGIN", "LUNCH_START", "LUNCH_END", "SHIFT_END"), ShiftSession.current(ended, x -> x),
                "shift qui vient de se terminer");
    }

    @Test
    void newShiftOpensWithThePlanningNotBefore() {
        LocalDateTime endedStart = DAY.atTime(7, 55);
        assertFalse(ShiftSession.mayStartNewShift(endedStart, DAY.atTime(19, 0), LocalTime.of(8, 0)), "même shift, le soir");
        assertTrue(ShiftSession.mayStartNewShift(endedStart, DAY.plusDays(1).atTime(7, 50), LocalTime.of(8, 0)), "lendemain matin");
        assertTrue(ShiftSession.mayStartNewShift(endedStart, DAY.plusDays(1).atTime(6, 0), LocalTime.of(8, 0)), "2 h avant : accepté");
        assertFalse(ShiftSession.mayStartNewShift(endedStart, DAY.plusDays(1).atTime(5, 30), LocalTime.of(8, 0)), "trop tôt");
        // nuit (21h-07h) : terminée ce matin, la suivante ce soir seulement
        LocalDateTime night = DAY.minusDays(1).atTime(20, 50);
        assertFalse(ShiftSession.mayStartNewShift(night, DAY.atTime(9, 0), LocalTime.of(21, 0)));
        assertTrue(ShiftSession.mayStartNewShift(night, DAY.atTime(20, 40), LocalTime.of(21, 0)));
        // repos au planning : un autre jour seulement
        assertFalse(ShiftSession.mayStartNewShift(endedStart, DAY.atTime(18, 0), null));
        assertTrue(ShiftSession.mayStartNewShift(endedStart, DAY.plusDays(1).atTime(10, 0), null));
    }

    @Test
    void autoClosedShiftThenReconnectingTheSameEveningDoesNotOpenANewShift() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = now.toLocalDate();
        // shift du jour terminé il y a peu : connexion 10 h 30 plus tôt, clôturé automatiquement
        LocalDateTime login = now.minusHours(11);
        planned(login.toLocalDate(), "M2", login.getHour(), (login.getHour() + 9) % 24);
        event("LOGIN", login);
        assertEquals(1, service.autoCloseOverflows());
        LocalDateTime endedAt = stored.get(stored.size() - 1).getOccurredAt();
        assertEquals(endedAt, service.shiftEndedAt("agent.mail"), "l'agent est déconnecté à la clôture");

        if (login.toLocalDate().equals(today)) {
            int before = stored.size();
            service.recordLogin(user);
            assertEquals(before, stored.size(), "reconnexion le même jour : pas de nouveau shift");
            assertEquals("SHIFT_ENDED", service.getStatus("agent.mail").currentState());
        }
    }

    @Test
    void nightShiftStaysOneShiftAcrossMidnightAndIsClosedNextMorning() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime login = now.minusHours(12).truncatedTo(ChronoUnit.HOURS); // shift de 9 h commencé il y a 12 h
        planned(login.toLocalDate(), "N", login.getHour(), (login.getHour() + 9) % 24);
        event("LOGIN", login);
        event("PAUSE_START", login.plusHours(4));
        event("PAUSE_END", login.plusHours(4).plusMinutes(15));
        assertEquals("WORKING", service.getStatus("agent.mail").currentState(), "toujours le même shift, même après minuit");
        assertEquals(1, service.autoCloseOverflows());
        assertEquals(login.plusHours(9).plusMinutes(90), stored.get(stored.size() - 1).getOccurredAt());
    }

    @Test
    void nextDayLoginStartsTheShiftOfThePlanning() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime yesterday = now.minusHours(24);
        event("LOGIN", yesterday);
        event("SHIFT_END", yesterday.plusHours(9));
        planned(now.toLocalDate(), "M", now.minusMinutes(10).getHour(), (now.getHour() + 8) % 24);
        assertEquals("NOT_STARTED", service.getStatus("agent.mail").currentState(), "shift du jour pas encore commencé");
        service.recordLogin(user);
        var status = service.getStatus("agent.mail");
        assertEquals("WORKING", status.currentState());
        assertEquals(1, status.todayEvents().size(), "nouveau shift : seuls ses propres pointages");
        assertNull(service.shiftEndedAt("agent.mail"));
    }
}

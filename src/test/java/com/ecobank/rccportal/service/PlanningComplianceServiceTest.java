package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.ShiftEvent;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.WorkflowRequest;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.dto.PlanningComplianceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Retards/absences calculés sur le planning propre à chaque agent. */
class PlanningComplianceServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    private AgentScheduleRepository schedules;
    private ShiftEventRepository events;
    private WorkflowRequestRepository workflows;
    private PlanningComplianceService service;
    private final List<AgentSchedule> scheduleRows = new ArrayList<>();
    private final List<ShiftEvent> eventRows = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        schedules = mock(AgentScheduleRepository.class);
        events = mock(ShiftEventRepository.class);
        workflows = mock(WorkflowRequestRepository.class);
        ShiftSwapRequestRepository swaps = mock(ShiftSwapRequestRepository.class);
        UserRoleRepository roles = mock(UserRoleRepository.class);
        when(schedules.findByWorkDate(DAY)).thenAnswer(i -> scheduleRows);
        when(events.findByOccurredAtBetweenOrderByUser_UsernameAscOccurredAtAsc(any(), any())).thenAnswer(i -> eventRows);
        when(workflows.findByTypeAndStatus("LEAVE", "APPROVED")).thenReturn(new ArrayList<>());
        service = new PlanningComplianceService(schedules, events, workflows, swaps, mock(UserRepository.class), roles);
        at(10, 0);
    }

    private void at(int h, int m) {
        service.setClock(Clock.fixed(DAY.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    }

    private User agent(String username) {
        User u = new User();
        u.setId(nextId++);
        u.setUsername(username);
        u.setName(username);
        u.setActivity("INBOUND_VOICE");
        return u;
    }

    private void plan(User u, String code, LocalTime start, LocalTime end) {
        scheduleRows.add(AgentSchedule.builder().user(u).workDate(DAY).shiftCode(code)
                .plannedStartTime(start).plannedEndTime(end).approvalStatus("APPROVED").build());
    }

    private void event(User u, String type, int h, int m) {
        eventRows.add(ShiftEvent.builder().user(u).eventType(type).occurredAt(DAY.atTime(h, m)).build());
    }

    private PlanningComplianceResponse only(String username) {
        return service.forDate(DAY, null).stream().filter(r -> r.username().equals(username)).findFirst().orElseThrow();
    }

    @Test
    void eachAgentIsJudgedAgainstHisOwnShift() {
        User morning = agent("matin");      // shift 07h
        User later = agent("apresmidi");    // shift 12h
        plan(morning, "M", LocalTime.of(7, 0), LocalTime.of(16, 0));
        plan(later, "A", LocalTime.of(12, 0), LocalTime.of(21, 0));
        event(morning, "LOGIN", 7, 40);

        at(10, 0);
        assertEquals("LATE", only("matin").status());
        assertEquals(40, only("matin").lateMinutes());
        // 10h00 : l'agent de l'après-midi n'est ni en retard ni absent.
        assertEquals("NOT_YET", only("apresmidi").status());
    }

    @Test
    void noLoginAnHourAfterPlannedStartIsAbsent() {
        User u = agent("absent");
        plan(u, "M2", LocalTime.of(8, 0), LocalTime.of(17, 0));
        at(8, 30);
        assertEquals("NOT_YET", only("absent").status());
        at(9, 5);
        assertEquals("ABSENT", only("absent").status());
    }

    @Test
    void loginWithinThresholdIsOnTime() {
        User u = agent("ponctuel");
        plan(u, "M2", LocalTime.of(8, 0), LocalTime.of(17, 0));
        event(u, "LOGIN", 8, 4);
        assertEquals("ON_TIME", only("ponctuel").status());
    }

    @Test
    void approvedLeaveIsNeverAbsence() {
        User u = agent("conge");
        plan(u, "M2", LocalTime.of(8, 0), LocalTime.of(17, 0));
        WorkflowRequest leave = new WorkflowRequest();
        leave.setRequestedBy(u);
        leave.setPeriodFrom(DAY.minusDays(1));
        leave.setPeriodTo(DAY.plusDays(2));
        when(workflows.findByTypeAndStatus("LEAVE", "APPROVED")).thenReturn(List.of(leave));
        at(11, 0);
        assertEquals("LEAVE", only("conge").status());
    }

    @Test
    void offAndPlannedAbsenceCodes() {
        User off = agent("repos");
        User abs = agent("abs");
        User sick = agent("maladie");
        plan(off, "OFF", null, null);
        plan(abs, "ABS", null, null);
        plan(sick, "RM", null, null);
        assertEquals("OFF", only("repos").status());
        assertEquals("PLANNED_ABSENCE", only("abs").status());
        assertEquals("LEAVE", only("maladie").status());
    }

    @Test
    void pendingPlanningDoesNotCountAndConnectedAgentIsUnplanned() {
        User u = agent("sansplanning");
        scheduleRows.add(AgentSchedule.builder().user(u).workDate(DAY).shiftCode("M")
                .plannedStartTime(LocalTime.of(7, 0)).approvalStatus("PENDING").build());
        event(u, "LOGIN", 9, 0);
        assertEquals("UNPLANNED", only("sansplanning").status());
    }

    @Test
    void earlyShiftEndIsReported() {
        User u = agent("parti");
        plan(u, "M2", LocalTime.of(8, 0), LocalTime.of(17, 0));
        event(u, "LOGIN", 8, 0);
        event(u, "SHIFT_END", 15, 30);
        at(18, 0);
        var r = only("parti");
        assertEquals("ON_TIME", r.status());
        assertEquals(90, r.earlyLeaveMinutes());
    }

    @Test
    void mostUrgentCasesComeFirst() {
        User ok = agent("a_ok");
        User late = agent("b_late");
        User absent = agent("c_absent");
        plan(ok, "M2", LocalTime.of(8, 0), LocalTime.of(17, 0));
        plan(late, "M2", LocalTime.of(8, 0), LocalTime.of(17, 0));
        plan(absent, "M2", LocalTime.of(8, 0), LocalTime.of(17, 0));
        event(ok, "LOGIN", 8, 0);
        event(late, "LOGIN", 8, 30);
        List<String> order = service.forDate(DAY, null).stream().map(PlanningComplianceResponse::status).toList();
        assertEquals(List.of("ABSENT", "LATE", "ON_TIME"), order);
    }
}

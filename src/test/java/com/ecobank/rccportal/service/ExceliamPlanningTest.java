package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AgentScheduleRepository;
import com.ecobank.rccportal.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.LocalTime;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Planning Exceliam d'octobre (Team Inbound Voix) : pas de légende, les horaires sont dans les lignes TOTAL. */
class ExceliamPlanningTest {

    @Test
    void hoursComeFromTheTotalRows() {
        List<List<String>> grid = List.of(
                List.of("", "TOTAL M (07H - 16H)", "4"), List.of("", "TOTAL M2 (08H - 17H)", "7"), List.of("", "TOTAL M3 (09H - 18H)", "0"),
                List.of("", "TOTAL M4 (10H - 19H)", "2"), List.of("", "TOTAL A (12H - 21H)", "5"), List.of("", "TOTAL N (21H - 07H)", "2"),
                List.of("L", "TOTAL SHIFTS", "20"));
        Map<String, ScheduleService.ShiftDef> legend = new LinkedHashMap<>();
        ScheduleService.addTotalRowsAndDefaults(grid, legend);
        assertEquals(LocalTime.of(7, 0), legend.get("M").start());
        assertEquals(LocalTime.of(19, 0), legend.get("M4").end());
        assertEquals(LocalTime.of(7, 0), legend.get("N").end());
        assertTrue(legend.get("N").overnight());
        assertEquals("Congé", legend.get("C").label());                 // codes sans horaire connus par défaut
        assertEquals("Repos maladie", legend.get("RM").label());
        assertNull(legend.get("C").start());
    }

    @Test
    void annotationsAfterTheNameAreSetAside() {
        assertArrayEquals(new String[]{"HADDAD KOUASSI JOSEPH CHRIST RAPHAEL", "Premium"},
                ScheduleService.splitNameAnnotation("HADDAD KOUASSI JOSEPH CHRIST RAPHAEL Premium"));
        assertArrayEquals(new String[]{"KONE NANGBAMA KINAYA CECILIA", "Premium vendredi et samedi"},
                ScheduleService.splitNameAnnotation("KONE NANGBAMA KINAYA CECILIA Premium vendredi et samedi"));
        assertArrayEquals(new String[]{"KOFFI épouse ELOGNE AKISSI JOSETTE ARMANDE", "Stage"},
                ScheduleService.splitNameAnnotation("KOFFI épouse ELOGNE AKISSI JOSETTE ARMANDE Stage"));
        assertArrayEquals(new String[]{"KOUAME KOUADIO JEAN", "Aghien"}, ScheduleService.splitNameAnnotation("KOUAME KOUADIO JEAN Aghien"));
        assertNull(ScheduleService.splitNameAnnotation("OUATTARA ADJARA")[1]);
    }

    @Test
    void monthIsDeducedFromTheFirstDay() {
        assertEquals(YearMonth.of(2026, 10), ScheduleService.guessMonth("Thu 01", 31, YearMonth.of(2026, 9)));
        assertEquals(YearMonth.of(2026, 11), ScheduleService.guessMonth("Sun 01", 30, YearMonth.of(2026, 9)));
        assertEquals(YearMonth.of(2026, 10), ScheduleService.guessMonth("Jeu 01", 31, YearMonth.of(2026, 9)));
    }

    /** Le planning d'octobre transcrit de la capture passe par le vrai import, sans choisir le mois. */
    @Test
    void octoberInboundVoicePlanningImports() throws Exception {
        AgentScheduleRepository schedules = mock(AgentScheduleRepository.class);
        UserRepository users = mock(UserRepository.class);
        ImportIntelligenceService ai = mock(ImportIntelligenceService.class);
        List<User> known = new ArrayList<>(List.of(
                User.builder().id(1L).username("haddad").name("Haddad Kouassi Joseph Christ Raphael").build(),
                User.builder().id(2L).username("kone").name("KONE NANGBAMA KINAYA CECILIA").build()));
        AtomicLong ids = new AtomicLong(100);
        when(users.findAll()).thenReturn(known);
        when(users.save(any(User.class))).thenAnswer(i -> { User u = i.getArgument(0); if (u.getId() == null) u.setId(ids.incrementAndGet()); return u; });
        when(schedules.findByUserAndWorkDate(any(), any())).thenReturn(Optional.empty());
        List<AgentSchedule> saved = new ArrayList<>();
        when(schedules.save(any(AgentSchedule.class))).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
        ScheduleService svc = new ScheduleService(schedules, null, users, null, null, ai, mock(AuditLogService.class), null, null);

        byte[] csv = getClass().getResourceAsStream("/plannings/planning_inbound_voix_octobre_2026.csv").readAllBytes();
        var result = svc.importFromExcel(new MockMultipartFile("file", "planning_inbound_voix_octobre_2026.csv", "text/csv", csv),
                null, "CI", "INBOUND VOICE", YearMonth.of(2026, 9), "exceliam");   // septembre pré-rempli à l'écran : corrigé en octobre

        assertEquals(32, result.rowsProcessed());
        assertEquals(32 * 31, result.entriesCreated());
        assertEquals(30, result.usersAutoCreated());                    // 2 agents déjà connus, reconnus malgré « Premium … »
        AgentSchedule first = saved.stream().filter(a -> a.getUser().getId() == 1L && a.getWorkDate().equals(LocalDate.of(2026, 10, 1))).findFirst().orElseThrow();
        assertEquals("A", first.getShiftCode());
        assertEquals(LocalTime.of(12, 0), first.getPlannedStartTime());
        AgentSchedule night = saved.stream().filter(a -> "N".equals(a.getShiftCode())).findFirst().orElseThrow();
        assertEquals(LocalTime.of(7, 0), night.getPlannedEndTime());   // horaire lu dans « TOTAL N (21H - 07H) »
        assertTrue(saved.stream().noneMatch(a -> a.getUser().getName().contains("Premium") || a.getUser().getName().contains("Aghien")));
        assertEquals(2, saved.stream().filter(a -> "RM".equals(a.getShiftCode())).count());
        assertTrue(saved.stream().allMatch(a -> a.getWorkDate().getMonthValue() == 10));
    }
}

package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AgentScheduleResponse;
import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AgentScheduleRepository;
import com.ecobank.rccportal.repository.UserRepository;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Le planning exporté par le Team Leader reprend la grille Exceliam et se réimporte tel quel. */
class PlanningExportTest {

    private static AgentScheduleResponse e(String user, String name, LocalDate d, String code, LocalTime s, LocalTime f) {
        return new AgentScheduleResponse(user, name, "INBOUND TCHAT", d, s, f, code, code, false, "APPROVED", null, "TEAM_LEADER");
    }

    @Test
    void exportedPlanningIsTheExceliamGridAndReimports() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 10, 1), d2 = d1.plusDays(1), last = d1.plusDays(6);
        Map<String, Map<LocalDate, AgentScheduleResponse>> byAgent = new HashMap<>();
        byAgent.put("amy", Map.of(d1, e("amy", "NDIAYE Biteye Amy", d1, "A", LocalTime.of(12, 0), LocalTime.of(21, 0)), d2, e("amy", "NDIAYE Biteye Amy", d2, "OFF", null, null)));
        byAgent.put("bok", Map.of(d1, e("bok", "BOKOUMGOU Marietou", d1, "N", LocalTime.of(21, 0), LocalTime.of(7, 0))));
        byte[] xlsx = PlanningExportService.workbook("Tchat", d1, last,
                List.of(new String[]{"bok", "BOKOUMGOU Marietou"}, new String[]{"amy", "NDIAYE Biteye Amy"}), byAgent);

        Sheet sh = WorkbookFactory.create(new ByteArrayInputStream(xlsx)).getSheetAt(0);
        assertEquals("Thu 01", sh.getRow(2).getCell(2).getStringCellValue());
        assertEquals("BOKOUMGOU Marietou", sh.getRow(4).getCell(1).getStringCellValue());
        assertEquals("N", sh.getRow(4).getCell(2).getStringCellValue());
        assertEquals("OFF", sh.getRow(5).getCell(3).getStringCellValue());
        assertEquals("TOTAL M (07H - 16H)", sh.getRow(6).getCell(1).getStringCellValue());   // ordre du planning Exceliam

        // Réimport par le vrai import du planning (sans choisir le mois) : mêmes codes, mêmes horaires.
        AgentScheduleRepository schedules = mock(AgentScheduleRepository.class);
        UserRepository users = mock(UserRepository.class);
        List<User> known = List.of(User.builder().id(1L).username("amy").name("NDIAYE Biteye Amy").build(),
                User.builder().id(2L).username("bok").name("BOKOUMGOU Marietou").build());
        when(users.findAll()).thenReturn(known);
        when(schedules.findByUserAndWorkDate(any(), any())).thenReturn(Optional.empty());
        List<AgentSchedule> saved = new ArrayList<>();
        when(schedules.save(any(AgentSchedule.class))).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
        ScheduleService svc = new ScheduleService(schedules, null, users, null, null, mock(ImportIntelligenceService.class), mock(AuditLogService.class), null, null);
        var r = svc.importFromExcel(new MockMultipartFile("file", "planning_tchat.xlsx", null, xlsx), null, null, null, null, "tl");
        assertEquals(2, r.rowsProcessed());
        assertEquals(0, r.usersAutoCreated());
        AgentSchedule night = saved.stream().filter(s -> "N".equals(s.getShiftCode())).findFirst().orElseThrow();
        assertEquals(LocalTime.of(7, 0), night.getPlannedEndTime());
        assertEquals(LocalDate.of(2026, 10, 1), night.getWorkDate());
    }
}

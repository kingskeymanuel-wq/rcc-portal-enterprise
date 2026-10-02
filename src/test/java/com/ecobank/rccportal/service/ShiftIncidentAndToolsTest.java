package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PlanningComplianceResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Alertes de shift (absence, débordement, dépassement de pause) et périmètre Team Leader des profils outils. */
class ShiftIncidentAndToolsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private static PlanningComplianceResponse row(String user, String status, String login, String end) {
        return new PlanningComplianceResponse(user, user.toUpperCase(), "INBOUND_VOICE", DAY, "J", "Journée", LocalTime.of(8, 0), LocalTime.of(17, 0), false,
                login == null ? null : LocalTime.parse(login), end == null ? null : LocalTime.parse(end), status, null, null, 0, false, "");
    }

    @Test
    void absencesOverflowsAndPauseOverrunsAreDetected() {
        List<PlanningComplianceResponse> rows = List.of(
                row("awa", "ABSENT", null, null),
                row("ibra", "ON_TIME", "07:58", "18:10"),      // débordement de 70 min
                row("myao", "ON_TIME", "08:01", "17:10"),      // 10 min : dans la tolérance
                row("serge", "LATE", "08:20", null));           // toujours en poste
        List<ShiftService.PauseOverrun> pauses = List.of(
                new ShiftService.PauseOverrun("myao", DAY, "PAUSE", DAY.atTime(10, 0), 28, 15),
                new ShiftService.PauseOverrun("myao", DAY, "LUNCH", DAY.atTime(13, 0), 75, 60),
                new ShiftService.PauseOverrun("inconnu", DAY, "PAUSE", DAY.atTime(10, 0), 40, 15)); // hors périmètre
        List<ShiftIncidentService.Incident> out = ShiftIncidentService.detect(rows, pauses, DAY.atTime(17, 45));

        assertTrue(out.stream().anyMatch(i -> i.username().equals("awa") && i.type().equals("ABSENCE")));
        ShiftIncidentService.Incident over = out.stream().filter(i -> i.username().equals("ibra")).findFirst().orElseThrow();
        assertEquals("DEBORDEMENT", over.type());
        assertEquals(70, over.minutes());
        assertTrue(out.stream().noneMatch(i -> i.username().equals("myao") && i.type().equals("DEBORDEMENT")), "10 min : toléré");
        ShiftIncidentService.Incident live = out.stream().filter(i -> i.username().equals("serge")).findFirst().orElseThrow();
        assertTrue(live.ongoing(), "toujours en poste après la fin prévue : débordement en cours");
        assertEquals(45, live.minutes());
        ShiftIncidentService.Incident pause = out.stream().filter(i -> i.type().equals("PAUSE")).findFirst().orElseThrow();
        assertEquals("myao", pause.username());
        assertEquals(13 + 15, pause.minutes(), "cumul des dépassements du jour");
        assertTrue(out.stream().noneMatch(i -> i.username().equals("inconnu")), "agents hors du périmètre ignorés");
        assertEquals("PAUSE|myao|2026-10-01", pause.key());
    }

    @Test
    void teamLeaderSeesHisTeamAndItsSubTeams() {
        assertTrue(ToolAccessService.inLedScope("OUTBOUND", "TELEVENTE"));
        assertTrue(ToolAccessService.inLedScope("OUTBOUND", "DIGITALISATION"));
        assertTrue(ToolAccessService.inLedScope("INBOUND_MAIL", "RAFIKI"));
        assertTrue(ToolAccessService.inLedScope("TELEVENTE", "TELEVENTE"));
        assertFalse(ToolAccessService.inLedScope("TELEVENTE", "DIGITALISATION"));
        assertFalse(ToolAccessService.inLedScope("INBOUND_VOICE", "CIB"));
        assertFalse(ToolAccessService.inLedScope(null, "CIB"));
    }
}

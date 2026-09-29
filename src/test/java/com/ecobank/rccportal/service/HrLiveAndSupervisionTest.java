package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.LiveShiftStatusResponse;
import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Portail RH en direct, journal de connexions par équipe et droits du workflow de supervision. */
class HrLiveAndSupervisionTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    private static AgentSchedule shift(String code, String start, String end) {
        AgentSchedule s = new AgentSchedule();
        s.setShiftCode(code);
        s.setWorkDate(DAY);
        if (start != null) s.setPlannedStartTime(LocalTime.parse(start));
        if (end != null) s.setPlannedEndTime(LocalTime.parse(end));
        return s;
    }

    private static LiveShiftStatusResponse live(String state) {
        return new LiveShiftStatusResponse("awa", "Awa", null, state, DAY.atTime(8, 2), null, null, 0, 0);
    }

    @Test
    void liveStatusFollowsTheTimeClockAndThePlanning() {
        LocalDateTime nine = DAY.atTime(9, 0);
        AgentSchedule m = shift("M", "08:00", "16:00");
        assertEquals("EN_POSTE", HrLiveService.statusOf(m, false, live("WORKING"), true, DAY, nine));
        assertEquals("EN_PAUSE", HrLiveService.statusOf(m, false, live("ON_LUNCH"), true, DAY, nine));
        assertEquals("DECONNECTE", HrLiveService.statusOf(m, false, live("DISCONNECTED"), true, DAY, nine));
        assertEquals("EN_RETARD", HrLiveService.statusOf(m, false, null, true, DAY, nine), "shift commencé, pas pointé");
        assertEquals("A_VENIR", HrLiveService.statusOf(shift("A", "13:00", "21:00"), false, null, true, DAY, nine));
        assertEquals("NON_POINTE", HrLiveService.statusOf(m, false, null, true, DAY, DAY.atTime(17, 0)));
        assertEquals("CONGE", HrLiveService.statusOf(m, true, live("WORKING"), true, DAY, nine), "le congé approuvé l'emporte");
        assertEquals("ABSENT", HrLiveService.statusOf(shift("ABS", null, null), false, null, true, DAY, nine));
        assertEquals("REPOS", HrLiveService.statusOf(shift("OFF", null, null), false, null, true, DAY, nine));
        assertEquals("NON_PLANIFIE", HrLiveService.statusOf(null, false, null, true, DAY, nine));
        assertEquals("PLANIFIE", HrLiveService.statusOf(m, false, null, false, DAY, nine), "autre jour : planning seul");
    }

    @Test
    void nightShiftCrossingMidnightIsStillInProgress() {
        AgentSchedule n = shift("N", "22:00", "06:00");
        assertEquals("EN_RETARD", HrLiveService.statusOf(n, false, null, true, DAY, DAY.atTime(23, 30)));
        assertEquals("A_VENIR", HrLiveService.statusOf(n, false, null, true, DAY, DAY.atTime(21, 0)));
    }

    @Test
    void shiftCodesAreClassified() {
        assertTrue(HrLiveService.isWorkingCode("M2"));
        assertTrue(HrLiveService.isWorkingCode("n"));
        assertFalse(HrLiveService.isWorkingCode("OFF"));
        assertFalse(HrLiveService.isWorkingCode("C"));
        assertFalse(HrLiveService.isWorkingCode("ABS"));
        assertEquals(12.5, HrLiveService.pct(1, 8));
        assertNull(HrLiveService.pct(1, 0));
    }

    @Test
    void loginJournalShowsTheRealTeam() {
        assertEquals("TCHAT", AuditService.teamOf("INBOUND MAIL", List.of("AGENT_TCHAT")));
        assertEquals("RAFIKI", AuditService.teamOf(null, List.of("AGENT_RAFIKI")));
        assertEquals("OUTBOUND", AuditService.teamOf("OUTBOUND", List.of()));
        assertEquals("RH", AuditService.teamOf(null, List.of("RH")));
        assertEquals("QA", AuditService.teamOf(null, List.of("QUALITY_ASSURANCE")));
        assertEquals("AUTRE", AuditService.teamOf(null, List.of()));
    }

    @Test
    void supervisionScopesFollowTheManager() {
        assertEquals(List.of("RCC", "QA"), SupervisionService.scopesOf(new AuthenticatedUser("sup", "SUPERVISOR", "SUPERVISEUR", "Sup")));
        assertEquals(List.of("QA"), SupervisionService.scopesOf(new AuthenticatedUser("hqa", "AGENT", "SUPERVISEUR_QA", "Head QA")));
        assertEquals(List.of("RCC", "QA"), SupervisionService.scopesOf(new AuthenticatedUser("adm", "ADMIN", null, "Admin")));
        assertTrue(SupervisionService.scopesOf(new AuthenticatedUser("tl", "TEAM_LEADER", "TEAM_LEADER_TCHAT", "TL")).isEmpty());

        AuthenticatedUser headQa = new AuthenticatedUser("hqa", "AGENT", "SUPERVISEUR_QA", "Head QA");
        assertEquals("QA", SupervisionService.resolveScope(headQa, null));
        assertThrows(ApiException.class, () -> SupervisionService.resolveScope(headQa, "RCC"), "le Head QA ne pilote pas les Team Leaders");
        assertThrows(ApiException.class, () -> SupervisionService.resolveScope(new AuthenticatedUser("a", "AGENT", "AGENT_INBOUND", "A"), null));
    }

    @Test
    void supervisionHelpers() {
        assertEquals("à valider", SupervisionService.label("A_VALIDER"));
        assertNull(SupervisionService.cut("   ", 10));
        assertEquals("abc", SupervisionService.cut(" abcdef ", 3));
    }
}

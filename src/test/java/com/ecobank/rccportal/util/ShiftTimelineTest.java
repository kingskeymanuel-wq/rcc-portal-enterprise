package com.ecobank.rccportal.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Déconnexion / reconnexion le même jour — logique pure, sans mock. */
class ShiftTimelineTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 9, 23, 0, 0);

    private static LocalDateTime at(int h, int m) {
        return DAY.withHour(h).withMinute(m);
    }

    private static ShiftTimeline timeline(Object... pairs) {
        List<ShiftTimeline.Event> events = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            events.add(new ShiftTimeline.Event((String) pairs[i], (LocalDateTime) pairs[i + 1]));
        }
        return ShiftTimeline.of(events);
    }

    @Test
    void logoutMakesAgentDisconnectedAndKeepsTheLastLogoutTime() {
        var t = timeline("LOGIN", at(8, 0), "LOGOUT", at(10, 0));
        assertEquals(ShiftTimeline.DISCONNECTED, t.state());
        assertEquals(at(10, 0), t.lastDisconnectedAt());
        assertNull(t.lastReconnectedAt());
        assertEquals(30, t.absenceMinutes(at(10, 30)));
    }

    @Test
    void reconnectionSameDayResumesTheTimerInContinuityAndTracksTheAbsence() {
        var t = timeline("LOGIN", at(8, 0), "LOGOUT", at(10, 0), "LOGIN", at(11, 15));
        assertEquals(ShiftTimeline.WORKING, t.state());
        // Minuteur continu : toujours depuis 08:00, pas depuis la reconnexion.
        assertEquals(at(8, 0), t.stateSince());
        assertEquals(at(10, 0), t.lastDisconnectedAt());
        assertEquals(at(11, 15), t.lastReconnectedAt());
        assertEquals(75, t.absenceMinutes(at(12, 0)));
        assertEquals(75, t.absenceMinutesInCurrentState(at(12, 0)));
    }

    @Test
    void absenceIsNeverCountedAsWorkedTime() {
        var t = timeline("LOGIN", at(8, 0), "LOGOUT", at(10, 0), "LOGIN", at(11, 0), "SHIFT_END", at(17, 0));
        assertEquals(2 * 60 + 6 * 60, t.workedMinutes(null));
        assertEquals(1, t.absences().size());
    }

    @Test
    void disconnectingDuringABreakRestoresTheBreakOnReconnection() {
        var t = timeline("LOGIN", at(8, 0), "PAUSE_START", at(10, 0), "LOGOUT", at(10, 5), "LOGIN", at(10, 20));
        assertEquals(ShiftTimeline.ON_PAUSE, t.state());
        assertEquals(at(10, 0), t.stateSince());
    }

    @Test
    void secondLoginWithoutRecordedLogoutNoLongerResetsAnything() {
        // Ancien bug : la 2e connexion remettait le minuteur à zéro et effaçait 08:00-11:00.
        var t = timeline("LOGIN", at(8, 0), "LOGIN", at(11, 0), "SHIFT_END", at(16, 0));
        assertEquals(8 * 60, t.workedMinutes(null));
        assertTrue(t.absences().isEmpty());
    }

    @Test
    void multipleDisconnectionsAreAllTracked() {
        var t = timeline("LOGIN", at(8, 0), "LOGOUT", at(9, 0), "LOGIN", at(9, 10),
                "LOGOUT", at(12, 0), "LOGIN", at(12, 30));
        assertEquals(2, t.absences().size());
        assertEquals(40, t.absenceMinutes(at(13, 0)));
        assertEquals(at(12, 30), t.lastReconnectedAt());
        assertEquals(at(8, 0), t.stateSince());
    }

    @Test
    void logoutAfterShiftEndIsIgnored() {
        var t = timeline("LOGIN", at(8, 0), "SHIFT_END", at(16, 0), "LOGOUT", at(16, 0));
        assertEquals(ShiftTimeline.SHIFT_ENDED, t.state());
        assertTrue(t.absences().isEmpty());
    }

    @Test
    void segmentsShowTheOfflinePeriod() {
        var t = timeline("LOGIN", at(8, 0), "LOGOUT", at(10, 0), "LOGIN", at(11, 0));
        var states = t.segments().stream().map(ShiftTimeline.Segment::state).toList();
        assertEquals(List.of(ShiftTimeline.WORKING, ShiftTimeline.DISCONNECTED, ShiftTimeline.WORKING), states);
        assertNull(t.segments().get(2).to());
    }
}

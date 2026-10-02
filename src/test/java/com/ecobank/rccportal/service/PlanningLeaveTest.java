package com.ecobank.rccportal.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** « Congé » posé au planning par le Team Leader : compté comme congé, jamais comme absence. */
class PlanningLeaveTest {

    @Test
    void congeCodeIsALeave() {
        assertEquals("CONGE", ScheduleService.LEAVE_CODE);
        assertTrue(PlanningComplianceService.isLeaveCode("CONGE", "Congé"));
        assertTrue(PlanningComplianceService.isLeaveCode("CONGE", "Congé (demande validée)"));
        assertFalse(PlanningComplianceService.isLeaveCode("OFF", "Repos hebdomadaire"));
        assertFalse(PlanningComplianceService.isLeaveCode("M", "Matin"));
    }
}

package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record AttendanceRecordResponse(
        Integer id,
        String matricule,
        String name,
        String team,
        LocalDate workDate,
        String status,
        LocalTime arrivalTime,
        LocalTime departureTime) {
}

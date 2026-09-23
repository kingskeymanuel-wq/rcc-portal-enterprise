package com.ecobank.rccportal.dto;

public record LeaveBalanceResponse(
        Long userId,
        String name,
        String username,
        String team,
        Integer year,
        Integer allocatedDays,
        Integer usedDays,
        Integer remainingDays
) {}

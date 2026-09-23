package com.ecobank.rccportal.dto;

import java.util.List;

public record PlanifyShiftsResult(
        int agentsPlanified,
        int entriesCreated,
        List<String> unknownUsernames
) {
}

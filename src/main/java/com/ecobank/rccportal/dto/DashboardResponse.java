package com.ecobank.rccportal.dto;

import java.util.List;

public record DashboardResponse(
        long users,
        long knowledgeBase,
        Integer training,
        Integer qa,
        List<DashboardActivityResponse> activities
) {
}

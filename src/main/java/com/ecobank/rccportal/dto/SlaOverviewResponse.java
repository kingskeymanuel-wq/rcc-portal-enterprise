package com.ecobank.rccportal.dto;

import java.util.List;

public record SlaOverviewResponse(
        int thresholdHours,
        List<SlaTeamStatsResponse> byTeamAndType,
        List<WorkflowRequestResponse> overdueRequests
) {}

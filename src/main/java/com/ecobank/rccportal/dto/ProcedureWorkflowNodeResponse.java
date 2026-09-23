package com.ecobank.rccportal.dto;

import java.util.List;

public record ProcedureWorkflowNodeResponse(
        Integer nodeId, Integer procedureId, String questionText, Boolean isStart,
        String suggestionLabel, String suggestionUrl,
        List<ProcedureWorkflowOptionResponse> options) {
}
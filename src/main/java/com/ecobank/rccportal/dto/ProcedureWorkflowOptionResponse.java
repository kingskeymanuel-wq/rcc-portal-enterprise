package com.ecobank.rccportal.dto;

public record ProcedureWorkflowOptionResponse(Integer optionId, String label, Integer nextNodeId, String outcome) {
}
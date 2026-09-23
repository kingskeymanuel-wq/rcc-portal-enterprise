package com.ecobank.rccportal.dto;

public record CreateWorkflowNodeRequest(
        String questionText, Boolean isStart, String suggestionLabel, String suggestionUrl) {
}
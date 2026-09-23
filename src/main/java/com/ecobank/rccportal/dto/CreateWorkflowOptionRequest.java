package com.ecobank.rccportal.dto;

/** outcome : "FIDELISATION" ou "CLOTURE" si cette option termine le parcours ; sinon nextNodeId. */
public record CreateWorkflowOptionRequest(Integer nodeId, String label, Integer nextNodeId, String outcome) {
}
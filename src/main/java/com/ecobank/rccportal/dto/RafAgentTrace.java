package com.ecobank.rccportal.dto;

/** Agent RAF consulté pour une question — affiché dans le widget (« Agents consultés »). */
public record RafAgentTrace(String id, String label, int routerScore, int matchScore, boolean used) {
}

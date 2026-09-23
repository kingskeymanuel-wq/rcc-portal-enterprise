package com.ecobank.rccportal.dto;

public record AgentTeamInfoResponse(
        String username,
        String fullName,
        String photoUrl,
        String team,
        String teamLabel,
        String teamLeaderUsername,
        String teamLeaderName
) {
}

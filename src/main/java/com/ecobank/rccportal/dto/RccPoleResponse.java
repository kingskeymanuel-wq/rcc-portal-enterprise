package com.ecobank.rccportal.dto;

import com.ecobank.rccportal.model.RccPole;

public record RccPoleResponse(
        Integer id,
        String name,
        ManagerSummary manager,
        String contactPhone,
        String contactEmail,
        String teamContactLabel,
        String whoWeAre,
        String whatWeDo,
        Boolean isActive,
        Integer sortOrder,
        int activityCount
) {
    public record ManagerSummary(String username, String name, String email) {
    }

    public static RccPoleResponse from(RccPole p, int activityCount) {
        ManagerSummary manager = p.getManager() != null
                ? new ManagerSummary(p.getManager().getUsername(), p.getManager().getName(), p.getManager().getEmail())
                : null;
        return new RccPoleResponse(
                p.getPoleId(),
                p.getName(),
                manager,
                p.getContactPhone(),
                p.getContactEmail(),
                p.getTeamContactLabel(),
                p.getWhoWeAre(),
                p.getWhatWeDo(),
                p.getIsActive(),
                p.getSortOrder(),
                activityCount
        );
    }
}

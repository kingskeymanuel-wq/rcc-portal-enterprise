package com.ecobank.rccportal.dto;

import java.util.List;

/**
 * Un groupe de fiches USERS partageant le même USERNAME (doublon logique) — voir
 * UserDeduplicationService. keepUserId désigne la fiche qui serait conservée
 * (celle avec le plus de champs non vides ; en cas d'égalité, la plus ancienne ID).
 */
public record DuplicateUserGroupResponse(
        String username,
        Long keepUserId,
        List<DuplicateUserEntryResponse> entries
) {
    public record DuplicateUserEntryResponse(
            Long userId,
            String name,
            String status,
            String activity,
            String ledTeam,
            String email,
            int filledFieldCount,
            boolean wouldBeKept
    ) {
    }
}

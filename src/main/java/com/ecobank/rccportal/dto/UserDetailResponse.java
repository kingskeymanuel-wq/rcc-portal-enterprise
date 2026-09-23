package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Fiche utilisateur complète : infos de base + rôles + services attribués,
 * en un seul appel (évite 3 requêtes séparées côté UI pour afficher une fiche).
 */
public record UserDetailResponse(
        Integer id,
        String username,
        String name,
        String email,
        Boolean active,
        List<RoleResponse> roles,
        List<RccServiceResponse> services,
        String affiliateBranch,
        String gender,
        String contractType,
        String contractStatus,
        LocalDate contractStartDate,
        String activity,
        String photoUrl,
        String ledTeam
) {
}
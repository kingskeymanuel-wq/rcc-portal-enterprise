package com.ecobank.rccportal.dto;

/**
 * Version allégée de UserResponse pour les endpoints ouverts à TOUT utilisateur connecté
 * (/api/users/directory, /api/users/search — sélection de destinataire messagerie/workflow,
 * recherche MON RCC). Exclut délibérément les champs RH sensibles présents sur UserResponse
 * (gender, contractType, contractStatus, contractStartDate, contractEndDate,
 * residencePlace) : ces données ne doivent être visibles que par RH/Admin, voir
 * UserController#contractsForHr. Ne contient que les champs effectivement consommés par le
 * frontend (qa.js, mon-rcc.js, workflow.js).
 */
public record UserDirectoryResponse(
        Integer id,
        String username,
        String fullName,
        String email,
        String role,
        String service,
        String affiliateBranch,
        Boolean active,
        String activity,
        String photoUrl
) {
}

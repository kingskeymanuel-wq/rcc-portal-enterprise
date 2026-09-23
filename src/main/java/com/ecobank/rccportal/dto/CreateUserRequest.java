package com.ecobank.rccportal.dto;

/**
 * Création directe d'un compte par un administrateur — actif immédiatement,
 * sans passer par le circuit d'auto-inscription/approbation. roleName et
 * serviceCode sont optionnels (attribuables aussi après coup via
 * AdministrationController).
 */
public record CreateUserRequest(
        String username,
        String name,
        String email,
        String affiliateBranch,
        String roleName,
        String serviceCode
) {
}

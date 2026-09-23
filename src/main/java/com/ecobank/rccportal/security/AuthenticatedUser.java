package com.ecobank.rccportal.security;

/**
 * Identité de l'utilisateur authentifié.
 *
 * Cette identité est construite depuis le JWT puis placée
 * dans le SecurityContext de Spring Security.
 *
 * Nouveau modèle RCC :
 *
 * USERS.USERNAME
 *      ↓
 * USER_ROLES -> ROLES
 *      ↓
 * USER_SERVICES -> SERVICES
 *
 * Attention :
 * les informations contenues ici proviennent du JWT.
 * Pour récupérer des informations fraîches depuis SQL Server,
 * utiliser UserRepository.findFirstByUsernameIgnoreCase(username()).
 */
public record AuthenticatedUser(
        String username,
        String role,
        String service,
        String name
) {
}
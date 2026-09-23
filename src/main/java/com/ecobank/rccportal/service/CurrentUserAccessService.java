package com.ecobank.rccportal.service;

import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vérifie les droits admin à partir de l'état ACTUEL en base (table USER_ROLES),
 * pas du rôle figé au moment du login dans le JWT.
 *
 * Pourquoi : le JWT ne se régénère qu'à la reconnexion. Un rôle ADMIN attribué
 * depuis Administration pendant qu'un agent est déjà connecté ne change rien à
 * son JWT existant — sans ce contrôle, toute action admin resterait bloquée
 * jusqu'à ce que la personne se déconnecte puis se reconnecte, ce qui n'est
 * pas évident à deviner depuis l'écran (le bouton semble simplement ne rien
 * faire). Le rôle du JWT reste utilisé pour l'affichage (menu, session.js) —
 * seule la décision d'autorisation serveur passe désormais par la base.
 */
@Service
public class CurrentUserAccessService {

    private final UserRoleRepository userRoleRepository;

    public CurrentUserAccessService(UserRoleRepository userRoleRepository) {
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(AuthenticatedUser requester) {
        if (requester == null || requester.username() == null || requester.username().isBlank()) {
            return false;
        }
        // Le JWT fait déjà foi si son rôle est ADMIN (évite une requête DB à chaque appel) ;
        // sinon on relit la base avant de refuser, au cas où le rôle vient d'être attribué.
        if ("admin".equalsIgnoreCase(requester.role())) {
            return true;
        }
        return userRoleRepository.findRolesByUsernameIgnoreCase(requester.username()).stream()
                .anyMatch(ur -> "admin".equalsIgnoreCase(ur.getRole().getName()));
    }

    public void requireAdmin(AuthenticatedUser requester) {
        if (!isAdmin(requester)) {
            throw ApiException.forbidden("Only an administrator can perform this action.");
        }
    }
}

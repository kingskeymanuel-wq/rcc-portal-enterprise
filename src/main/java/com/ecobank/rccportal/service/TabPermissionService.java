package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AllowedTabsResponse;
import com.ecobank.rccportal.dto.TabPermissionRequest;
import com.ecobank.rccportal.dto.TabPermissionResponse;
import com.ecobank.rccportal.model.TabPermission;
import com.ecobank.rccportal.repository.RoleRepository;
import com.ecobank.rccportal.repository.TabPermissionRepository;
import com.ecobank.rccportal.repository.TeamRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gestion des permissions d'accès aux onglets RCC.
 *
 * Une permission peut être associée :
 * - à un service/équipe ;
 * - ou à un rôle.
 *
 * Une règle avec IsAllowed = false interdit l'accès à l'onglet.
 * En l'absence de règle, l'onglet est autorisé par défaut.
 */
@Service
public class TabPermissionService {

    private final TabPermissionRepository tabPermissionRepository;
    private final TeamRepository teamRepository;
    private final RoleRepository roleRepository;

    public TabPermissionService(
            TabPermissionRepository tabPermissionRepository,
            TeamRepository teamRepository,
            RoleRepository roleRepository) {

        this.tabPermissionRepository = tabPermissionRepository;
        this.teamRepository = teamRepository;
        this.roleRepository = roleRepository;
    }

    /**
     * Retourne toutes les règles de permissions.
     */
    @Transactional(readOnly = true)
    public List<TabPermissionResponse> listAll() {

        return tabPermissionRepository
                .findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Retourne les onglets interdits pour
     * l'utilisateur actuellement connecté.
     *
     * AuthenticatedUser contient maintenant :
     * username / role / service / name.
     */
    @Transactional(readOnly = true)
    public AllowedTabsResponse resolveForUser(
            AuthenticatedUser user) {

        if (user == null) {
            throw ApiException.unauthorized(
                    "Authentication required."
            );
        }

        List<TabPermission> denied =
                tabPermissionRepository
                        .findDeniedRulesForTeamOrRole(
                                user.service(),
                                user.role()
                        );

        List<String> tabCodes =
                denied.stream()
                        .map(TabPermission::getTabCode)
                        .filter(code -> code != null && !code.isBlank())
                        .distinct()
                        .toList();

        return new AllowedTabsResponse(tabCodes);
    }

    /**
     * Crée ou met à jour une permission.
     *
     * Une permission doit cibler exactement :
     * - soit une équipe/service ;
     * - soit un rôle.
     */
    @Transactional
    public TabPermissionResponse upsert(
            TabPermissionRequest request) {

        if (request == null) {
            throw ApiException.badRequest(
                    "Permission request is required."
            );
        }

        boolean hasTeam =
                request.teamCode() != null &&
                        !request.teamCode().isBlank();

        boolean hasRole =
                request.roleCode() != null &&
                        !request.roleCode().isBlank();

        if (hasTeam == hasRole) {
            throw ApiException.badRequest(
                    "Provide exactly one of teamCode or roleCode, not both."
            );
        }

        if (request.tabCode() == null ||
                request.tabCode().isBlank()) {

            throw ApiException.badRequest(
                    "tabCode is required."
            );
        }

        String teamCode =
                hasTeam
                        ? request.teamCode().trim()
                        : null;

        String roleName =
                hasRole
                        ? request.roleCode().trim()
                        : null;

        String tabCode =
                request.tabCode().trim();

        /*
         * Recherche d'une règle existante afin
         * d'éviter les doublons.
         */
        TabPermission entity =
                tabPermissionRepository
                        .findExistingRule(
                                teamCode,
                                roleName,
                                tabCode
                        )
                        .orElseGet(
                                TabPermission::new
                        );

        entity.setTabCode(tabCode);

        entity.setIsAllowed(
                request.isAllowed()
        );

        /*
         * Permission basée sur TEAM/SERVICE.
         */
        if (hasTeam) {

            entity.setTeam(
                    teamRepository
                            .findByCode(teamCode)
                            .orElseThrow(
                                    () -> ApiException.badRequest(
                                            "Unknown team/service."
                                    )
                            )
            );

            entity.setRole(null);
        }

        /*
         * Permission basée sur ROLE.
         *
         * L'ancien modèle utilisait :
         *
         * Role.name
         *
         * Le nouveau modèle utilise :
         *
         * Role.name
         */
        else {

            entity.setRole(
                    roleRepository
                            .findByNameIgnoreCase(roleName)
                            .orElseThrow(
                                    () -> ApiException.badRequest(
                                            "Unknown role."
                                    )
                            )
            );

            entity.setTeam(null);
        }

        TabPermission saved =
                tabPermissionRepository.save(entity);

        return toResponse(saved);
    }

    /**
     * Supprime une permission.
     */
    @Transactional
    public void remove(Integer id) {

        if (id == null) {
            throw ApiException.badRequest(
                    "Permission id is required."
            );
        }

        if (!tabPermissionRepository.existsById(id)) {
            throw ApiException.notFound(
                    "Tab permission not found."
            );
        }

        tabPermissionRepository.deleteById(id);
    }

    /**
     * Conversion Entity -> DTO.
     */
    private TabPermissionResponse toResponse(
            TabPermission permission) {

        String teamCode =
                permission.getTeam() != null
                        ? permission.getTeam().getCode()
                        : null;

        /*
         * IMPORTANT :
         *
         * Role.getCode() n'existe plus.
         *
         * dbo.ROLES utilise maintenant :
         *
         * ID
         * NAME
         * DESCRIPTION
         */
        String roleName =
                permission.getRole() != null
                        ? permission.getRole().getName()
                        : null;

        return new TabPermissionResponse(
                permission.getTabPermissionId(),
                teamCode,
                roleName,
                permission.getTabCode(),
                Boolean.TRUE.equals(
                        permission.getIsAllowed()
                )
        );
    }
}
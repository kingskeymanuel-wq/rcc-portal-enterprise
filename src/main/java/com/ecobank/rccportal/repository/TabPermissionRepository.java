package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.TabPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TabPermissionRepository extends JpaRepository<TabPermission, Integer> {

    /**
     * Retourne toutes les règles de restriction (isAllowed = false)
     * applicables à une équipe ou à un rôle donné.
     *
     * IMPORTANT :
     * - Team possède un champ "code"
     * - Role possède un champ "name"
     */
    @Query("""
            SELECT tp
            FROM TabPermission tp
            WHERE tp.isAllowed = false
              AND (
                    (tp.team IS NOT NULL AND tp.team.code = :teamCode)
                    OR
                    (tp.role IS NOT NULL AND LOWER(tp.role.name) = LOWER(:roleCode))
                  )
            """)
    List<TabPermission> findDeniedRulesForTeamOrRole(
            @Param("teamCode") String teamCode,
            @Param("roleCode") String roleCode
    );

    /**
     * Recherche une règle existante correspondant :
     *
     * (teamCode OU roleCode) + tabCode
     *
     * Utilisé pour effectuer un véritable UPSERT et éviter
     * la création de doublons.
     */
    @Query("""
            SELECT tp
            FROM TabPermission tp
            WHERE tp.tabCode = :tabCode
              AND (
                    (
                        :teamCode IS NOT NULL
                        AND tp.team IS NOT NULL
                        AND tp.team.code = :teamCode
                    )
                    OR
                    (
                        :roleCode IS NOT NULL
                        AND tp.role IS NOT NULL
                        AND LOWER(tp.role.name) = LOWER(:roleCode)
                    )
                  )
            """)
    Optional<TabPermission> findExistingRule(
            @Param("teamCode") String teamCode,
            @Param("roleCode") String roleCode,
            @Param("tabCode") String tabCode
    );
}
package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRoleRepository
        extends JpaRepository<UserRole, Long> {

    List<UserRole> findByUser_Id(Long userId);

    boolean existsByUser_IdAndRole_Id(
            Long userId,
            Long roleId
    );

    @Query("""
            SELECT ur
            FROM UserRole ur
            JOIN FETCH ur.user
            WHERE LOWER(ur.role.name) = LOWER(:roleName)
            """)
    List<UserRole> findByRoleNameIgnoreCase(
            @Param("roleName") String roleName
    );

    @Query("""
            SELECT ur
            FROM UserRole ur
            JOIN FETCH ur.role
            WHERE ur.user.id = :userId
            """)
    List<UserRole> findRolesByUserId(
            @Param("userId") Long userId
    );

    /**
     * Rôles réels d'un utilisateur par username — utilisé pour vérifier les droits
     * admin à partir de l'état ACTUEL en base plutôt que du rôle figé dans le JWT
     * (voir CurrentUserAccessService : un rôle attribué après le login ne serait
     * sinon pas pris en compte tant que la personne ne s'est pas reconnectée).
     */
    @Query("""
            SELECT ur
            FROM UserRole ur
            JOIN FETCH ur.role
            WHERE LOWER(ur.user.username) = LOWER(:username)
            """)
    List<UserRole> findRolesByUsernameIgnoreCase(
            @Param("username") String username
    );

    /** Supprime toutes les attributions d'un rôle précis — étape obligatoire avant de
     *  pouvoir supprimer le rôle lui-même (contrainte de clé étrangère USER_ROLES → ROLES). */
    @org.springframework.data.jpa.repository.Modifying
    void deleteByRole_Id(Long roleId);
}
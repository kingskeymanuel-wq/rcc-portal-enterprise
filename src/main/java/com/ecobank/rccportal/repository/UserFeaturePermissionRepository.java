package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserFeaturePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserFeaturePermissionRepository extends JpaRepository<UserFeaturePermission, Integer> {
    List<UserFeaturePermission> findByUser(User user);
    Optional<UserFeaturePermission> findByUserAndFeatureCode(User user, String featureCode);
    void deleteByUserAndFeatureCode(User user, String featureCode);
}

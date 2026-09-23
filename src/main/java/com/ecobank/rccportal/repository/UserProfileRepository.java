package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Integer> {
    Optional<UserProfile> findByUser(User user);
}

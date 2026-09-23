package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository
        extends JpaRepository<User, Long> {

    Optional<User> findFirstByUsernameIgnoreCase(
            String username
    );

    boolean existsByUsernameIgnoreCase(
            String username
    );

    Optional<User> findByEmailIgnoreCase(
            String email
    );

    boolean existsByEmailIgnoreCase(
            String email
    );

    List<User> findByStatus(
            String status
    );
}
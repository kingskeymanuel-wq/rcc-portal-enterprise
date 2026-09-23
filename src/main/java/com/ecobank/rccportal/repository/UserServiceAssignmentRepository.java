package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.UserServiceAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserServiceAssignmentRepository
        extends JpaRepository<UserServiceAssignment, Long> {

    List<UserServiceAssignment> findByUserId(Long userId);

    boolean existsByUserIdAndServiceId(
            Long userId,
            Long serviceId
    );

    @Query("""
            SELECT us
            FROM UserServiceAssignment us
            JOIN FETCH us.service
            WHERE us.user.id = :userId
            ORDER BY us.id ASC
            """)
    List<UserServiceAssignment> findServicesByUserId(
            @Param("userId") Long userId
    );
}
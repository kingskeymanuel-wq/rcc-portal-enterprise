package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.LeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Integer> {
    Optional<LeaveBalance> findByUserIdAndYear(Long userId, Integer year);
    List<LeaveBalance> findByYear(Integer year);
}

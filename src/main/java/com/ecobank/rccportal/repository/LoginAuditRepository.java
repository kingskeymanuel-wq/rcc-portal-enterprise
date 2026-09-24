package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.LoginAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoginAuditRepository extends JpaRepository<LoginAudit, Integer> {

    List<LoginAudit> findTop10ByOrderByOccurredAtDesc();

    boolean existsByUser_Id(Long userId);

    List<LoginAudit> findAllByOrderByOccurredAtDesc(Pageable pageable);

}
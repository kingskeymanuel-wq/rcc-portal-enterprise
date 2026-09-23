package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.TrainingCertificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrainingCertificateRepository extends JpaRepository<TrainingCertificate, Long> {

    List<TrainingCertificate> findByUserIdOrderByRequestedAtDesc(Long userId);

    List<TrainingCertificate> findByStatusOrderByRequestedAtAsc(String status);

    List<TrainingCertificate> findAllByOrderByRequestedAtDesc();

    Optional<TrainingCertificate> findByCertificateNumberIgnoreCase(String certificateNumber);

    boolean existsByCertificateNumber(String certificateNumber);
}

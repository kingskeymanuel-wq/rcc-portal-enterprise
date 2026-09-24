package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.CardAgencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CardAgencyStatusRepository extends JpaRepository<CardAgencyStatus, Long> {

    List<CardAgencyStatus> findByCountryCodeIgnoreCaseOrderByReportDateDescAgencyAsc(String countryCode);

    Optional<CardAgencyStatus> findByCountryCodeIgnoreCaseAndReportDateAndAgencyIgnoreCase(String countryCode, LocalDate reportDate, String agency);

    long countByCountryCodeIgnoreCase(String countryCode);

    Optional<CardAgencyStatus> findFirstByCountryCodeIgnoreCaseAndReportDateAndAgencyCodeIgnoreCase(String countryCode, LocalDate reportDate, String agencyCode);
}

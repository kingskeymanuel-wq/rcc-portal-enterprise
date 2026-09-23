package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.BankBranch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BankBranchRepository extends JpaRepository<BankBranch, Long> {

    List<BankBranch> findByCountryCodeIgnoreCaseAndActiveTrueOrderByCityAscNameAsc(String countryCode);

    List<BankBranch> findByCountryCodeIgnoreCaseOrderByCityAscNameAsc(String countryCode);

    long countByCountryCodeIgnoreCaseAndActiveTrue(String countryCode);

    boolean existsByCountryCodeIgnoreCase(String countryCode);
}

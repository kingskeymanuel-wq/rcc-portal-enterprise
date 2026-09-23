package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.CardProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CardProductRepository extends JpaRepository<CardProduct, Long> {

    List<CardProduct> findByCountryCodeIgnoreCaseAndActiveTrueOrderBySortOrderAscNameAsc(String countryCode);
}

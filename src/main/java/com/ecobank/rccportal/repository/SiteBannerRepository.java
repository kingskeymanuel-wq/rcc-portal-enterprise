package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.SiteBanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SiteBannerRepository extends JpaRepository<SiteBanner, Integer> {
}

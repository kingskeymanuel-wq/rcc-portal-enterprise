package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.SiteSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SiteSettingRepository extends JpaRepository<SiteSetting, String> {
}

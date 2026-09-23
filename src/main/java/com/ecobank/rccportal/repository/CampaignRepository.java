package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, Integer> {

    List<Campaign> findAllByOrderByCreatedAtDesc();
}

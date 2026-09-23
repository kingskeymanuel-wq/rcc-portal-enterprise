package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.CampaignContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CampaignContactRepository extends JpaRepository<CampaignContact, Integer> {

    List<CampaignContact> findByCampaignIdOrderByClientNameAsc(Integer campaignId);

    List<CampaignContact> findByCampaignIdAndAgentUserIdOrderByCallStatusAscClientNameAsc(Integer campaignId, Long agentUserId);

    List<CampaignContact> findByAgentUserIdInOrderByCreatedAtDesc(List<Long> agentUserIds);

    List<CampaignContact> findByAgentUserIdOrderByCallStatusAscClientNameAsc(Long agentUserId);
}

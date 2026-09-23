package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RccCommunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RccCommunityRepository extends JpaRepository<RccCommunity, Integer> {
    List<RccCommunity> findAllByOrderBySortOrderAsc();
    Optional<RccCommunity> findByCommunityKeyIgnoreCase(String communityKey);
}

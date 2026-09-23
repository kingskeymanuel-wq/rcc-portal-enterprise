package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RccCommunityFollow;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RccCommunityFollowRepository extends JpaRepository<RccCommunityFollow, Integer> {

    List<RccCommunityFollow> findByUser(User user);

    Optional<RccCommunityFollow> findByUserAndCommunityKey(User user, String communityKey);
}

package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.ShiftSwapRequest;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShiftSwapRequestRepository extends JpaRepository<ShiftSwapRequest, Integer> {

    /** Demandes où l'agent est soit demandeur, soit visé — "Mes permutations" côté agent. */
    List<ShiftSwapRequest> findByRequesterOrTargetUserOrderByCreatedAtDesc(User requester, User targetUser);

    /** File d'attente de l'agent visé — décisions peer en attente. */
    List<ShiftSwapRequest> findByTargetUserAndPeerStatusOrderByCreatedAtAsc(User targetUser, String peerStatus);

    /** File d'attente du Team Leader — acceptées par le pair, en attente de sa propre décision. */
    List<ShiftSwapRequest> findByTeamLeaderStatusOrderByCreatedAtAsc(String teamLeaderStatus);
}

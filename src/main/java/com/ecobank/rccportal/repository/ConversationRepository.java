package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Integer> {
}

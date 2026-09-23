package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Conversation;
import com.ecobank.rccportal.model.ConversationParticipant;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationParticipantRepository extends JpaRepository<ConversationParticipant, Integer> {

    List<ConversationParticipant> findByUser(User user);

    List<ConversationParticipant> findByConversation(Conversation conversation);

    Optional<ConversationParticipant> findByConversationAndUser(Conversation conversation, User user);
}

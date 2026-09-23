package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.ChatMessage;
import com.ecobank.rccportal.model.Conversation;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Integer> {

    List<ChatMessage> findByConversationOrderBySentAtAsc(Conversation conversation);

    Optional<ChatMessage> findTopByConversationOrderBySentAtDesc(Conversation conversation);

    long countByConversationAndSenderNotAndSentAtAfter(Conversation conversation, User sender, LocalDateTime after);

    long countByConversationAndSenderNot(Conversation conversation, User sender);

    List<ChatMessage> findByExpiresAtBefore(LocalDateTime cutoff);
}

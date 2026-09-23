package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ConversationResponse(
        Integer id, String type, String displayName, List<String> memberNames,
        String lastMessage, LocalDateTime lastMessageAt, long unreadCount) {
}

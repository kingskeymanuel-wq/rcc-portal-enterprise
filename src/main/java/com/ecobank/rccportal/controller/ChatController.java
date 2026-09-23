package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.ChatMessageRequest;
import com.ecobank.rccportal.dto.ChatMessageResponse;
import com.ecobank.rccportal.dto.ConversationRequest;
import com.ecobank.rccportal.dto.ConversationResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/conversations")
    public List<ConversationResponse> listConversations(@AuthenticationPrincipal AuthenticatedUser requester) {
        return chatService.listMine(requester);
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse createConversation(@Valid @RequestBody ConversationRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser requester) {
        return chatService.createConversation(request, requester);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessageResponse> listMessages(@PathVariable Integer id,
                                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        return chatService.listMessages(id, requester);
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatMessageResponse sendMessage(@PathVariable Integer id, @Valid @RequestBody ChatMessageRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        return chatService.sendMessage(id, request, requester);
    }

    /** L'expéditeur peut supprimer son propre message. */
    @DeleteMapping("/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMessage(@PathVariable Integer messageId, @AuthenticationPrincipal AuthenticatedUser requester) {
        chatService.deleteMessage(messageId, requester);
    }

    @PostMapping("/conversations/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        chatService.markRead(id, requester);
    }
}

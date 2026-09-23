package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.ChatMessageRequest;
import com.ecobank.rccportal.dto.ChatMessageResponse;
import com.ecobank.rccportal.dto.ConversationRequest;
import com.ecobank.rccportal.dto.ConversationResponse;
import com.ecobank.rccportal.model.ChatMessage;
import com.ecobank.rccportal.model.Conversation;
import com.ecobank.rccportal.model.ConversationParticipant;
import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.ChatMessageRepository;
import com.ecobank.rccportal.repository.ConversationParticipantRepository;
import com.ecobank.rccportal.repository.ConversationRepository;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;
    private final RccNotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final com.ecobank.rccportal.repository.UserProfileRepository userProfileRepository;

    public ChatService(
            ConversationRepository conversationRepository,
            ConversationParticipantRepository participantRepository,
            ChatMessageRepository messageRepository,
            RccNotificationRepository notificationRepository,
            UserRepository userRepository,
            com.ecobank.rccportal.repository.UserProfileRepository userProfileRepository) {

        this.userProfileRepository = userProfileRepository;
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    /**
     * Liste les conversations de l'utilisateur connecté.
     */
    @Transactional(readOnly = true)
    public List<ConversationResponse> listMine(
            AuthenticatedUser requester) {

        User me = requireUser(requester);

        return participantRepository
                .findByUser(me)
                .stream()
                .map(participant ->
                        toResponse(
                                participant.getConversation(),
                                me,
                                participant
                        )
                )
                .sorted(
                        Comparator.comparing(
                                ConversationResponse::lastMessageAt,
                                Comparator.nullsLast(
                                        Comparator.reverseOrder()
                                )
                        )
                )
                .toList();
    }

    /**
     * Liste les messages d'une conversation.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> listMessages(
            Integer conversationId,
            AuthenticatedUser requester) {

        User me = requireUser(requester);

        Conversation conversation =
                requireConversation(conversationId);

        requireMembership(conversation, me);

        return messageRepository
                .findByConversationOrderBySentAtAsc(
                        conversation
                )
                .stream()
                .filter(message -> message.getExpiresAt() == null || message.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(message ->
                        toResponse(message, me)
                )
                .toList();
    }

    /**
     * Envoie un message.
     */
    @Transactional
    public ChatMessageResponse sendMessage(
            Integer conversationId,
            ChatMessageRequest request,
            AuthenticatedUser requester) {

        User me = requireUser(requester);

        Conversation conversation =
                requireConversation(conversationId);

        ConversationParticipant myParticipant =
                requireMembership(conversation, me);

        if ((request.content() == null || request.content().isBlank())
                && (request.mediaUrl() == null || request.mediaUrl().isBlank())) {

            throw ApiException.badRequest(
                    "Message content or media is required."
            );
        }

        LocalDateTime expiresAt = (request.ephemeralMinutes() != null && request.ephemeralMinutes() > 0)
                ? LocalDateTime.now().plusMinutes(request.ephemeralMinutes())
                : null;

        ChatMessage message =
                ChatMessage.builder()
                        .conversation(conversation)
                        .sender(me)
                        .content(
                                request.content() != null ? request.content().trim() : ""
                        )
                        .mediaUrl(request.mediaUrl())
                        .expiresAt(expiresAt)
                        .sentAt(
                                LocalDateTime.now()
                        )
                        .build();

        message =
                messageRepository.save(message);

        myParticipant.setLastReadAt(
                message.getSentAt()
        );

        participantRepository.save(
                myParticipant
        );

        notifyOtherParticipants(conversation, me, message.getContent());

        return toResponse(
                message,
                me
        );
    }

    /** L'expéditeur peut retirer son propre message — remplacé par un message technique visible côté destinataire. */
    @Transactional
    public void deleteMessage(Integer messageId, AuthenticatedUser requester) {
        User me = requireUser(requester);
        ChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> ApiException.notFound("Message introuvable."));
        if (!message.getSender().getId().equals(me.getId())) {
            throw ApiException.forbidden("Vous ne pouvez supprimer que vos propres messages.");
        }
        messageRepository.delete(message);
    }

    /** Une notification personnelle par destinataire — pas de notification globale pour un message privé. */
    private void notifyOtherParticipants(Conversation conversation, User sender, String content) {
        String preview = content.length() > 80 ? content.substring(0, 80) + "…" : content;
        String senderLabel = sender.getName() != null ? sender.getName() : sender.getUsername();

        for (ConversationParticipant participant : participantRepository.findByConversation(conversation)) {
            if (participant.getUser().getId().equals(sender.getId())) {
                continue;
            }
            RccNotification notification = RccNotification.builder()
                    .targetUser(participant.getUser())
                    .content("Nouveau message de " + senderLabel + " : " + preview)
                    .isRead(false)
                    .build();
            notificationRepository.save(notification);
        }
    }

    /**
     * Crée une conversation DM ou GROUP.
     *
     * Le DTO utilise encore memberMatricules
     * pour compatibilité frontend.
     *
     * Les valeurs sont désormais interprétées
     * comme USERS.USERNAME.
     */
    @Transactional
    public ConversationResponse createConversation(
            ConversationRequest request,
            AuthenticatedUser requester) {

        User me = requireUser(requester);

        if (request.type() == null
                || request.type().isBlank()) {

            throw ApiException.badRequest(
                    "type is required."
            );
        }

        String type =
                request.type()
                        .trim()
                        .toUpperCase();

        if (!type.equals("DM")
                && !type.equals("GROUP")) {

            throw ApiException.badRequest(
                    "type must be DM or GROUP."
            );
        }

        if (request.memberMatricules() == null) {

            throw ApiException.badRequest(
                    "At least one member is required."
            );
        }

        List<User> others =
                request.memberMatricules()
                        .stream()
                        .filter(value -> value != null)
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .distinct()

                        /*
                         * Ne pas ajouter l'utilisateur
                         * connecté une seconde fois.
                         */
                        .filter(username ->
                                !username.equalsIgnoreCase(
                                        me.getUsername()
                                )
                        )

                        .map(username ->
                                userRepository
                                        .findFirstByUsernameIgnoreCase(
                                                username
                                        )
                                        .orElseThrow(
                                                () ->
                                                        ApiException.badRequest(
                                                                "Unknown member: "
                                                                        + username
                                                        )
                                        )
                        )
                        .toList();

        if (others.isEmpty()) {

            throw ApiException.badRequest(
                    "At least one other member is required."
            );
        }

        /*
         * Conversation privée.
         */
        if ("DM".equals(type)) {

            if (others.size() != 1) {

                throw ApiException.badRequest(
                        "A DM conversation must have exactly one other member."
                );
            }

            User other =
                    others.get(0);

            Optional<Conversation> existing =
                    findExistingDm(
                            me,
                            other
                    );

            Conversation conversation =
                    existing.orElseGet(
                            () -> {

                                Conversation created =
                                        conversationRepository.save(
                                                Conversation.builder()
                                                        .type("DM")
                                                        .build()
                                        );

                                participantRepository.save(
                                        ConversationParticipant.builder()
                                                .conversation(created)
                                                .user(me)
                                                .build()
                                );

                                participantRepository.save(
                                        ConversationParticipant.builder()
                                                .conversation(created)
                                                .user(other)
                                                .build()
                                );

                                return created;
                            }
                    );

            ConversationParticipant myParticipant =
                    requireMembership(
                            conversation,
                            me
                    );

            return toResponse(
                    conversation,
                    me,
                    myParticipant
            );
        }

        /*
         * Conversation de groupe.
         */
        if (request.name() == null
                || request.name().isBlank()) {

            throw ApiException.badRequest(
                    "name is required for a group conversation."
            );
        }

        Conversation conversation =
                conversationRepository.save(
                        Conversation.builder()
                                .type("GROUP")
                                .name(
                                        request.name().trim()
                                )
                                .build()
                );

        participantRepository.save(
                ConversationParticipant.builder()
                        .conversation(conversation)
                        .user(me)
                        .build()
        );

        for (User other : others) {

            participantRepository.save(
                    ConversationParticipant.builder()
                            .conversation(conversation)
                            .user(other)
                            .build()
            );
        }

        ConversationParticipant myParticipant =
                requireMembership(
                        conversation,
                        me
                );

        return toResponse(
                conversation,
                me,
                myParticipant
        );
    }

    /**
     * Marque une conversation comme lue.
     */
    @Transactional
    public void markRead(
            Integer conversationId,
            AuthenticatedUser requester) {

        User me = requireUser(requester);

        Conversation conversation =
                requireConversation(
                        conversationId
                );

        ConversationParticipant participant =
                requireMembership(
                        conversation,
                        me
                );

        participant.setLastReadAt(
                LocalDateTime.now()
        );

        participantRepository.save(
                participant
        );
    }

    /**
     * Recherche une conversation DM existante
     * entre deux utilisateurs.
     */
    private Optional<Conversation> findExistingDm(
            User userA,
            User userB) {

        return participantRepository
                .findByUser(userA)
                .stream()
                .map(
                        ConversationParticipant::getConversation
                )
                .filter(conversation ->
                        "DM".equals(
                                conversation.getType()
                        )
                )
                .filter(conversation ->
                        participantRepository
                                .findByConversationAndUser(
                                        conversation,
                                        userB
                                )
                                .isPresent()
                )
                .findFirst();
    }

    /**
     * Recherche une conversation.
     */
    private Conversation requireConversation(
            Integer conversationId) {

        return conversationRepository
                .findById(conversationId)
                .orElseThrow(
                        () ->
                                ApiException.notFound(
                                        "Conversation not found."
                                )
                );
    }

    /**
     * Récupère l'utilisateur connecté.
     *
     * IMPORTANT :
     * requester.username() reste temporairement
     * le nom du champ dans AuthenticatedUser.
     *
     * Sa valeur correspond maintenant à
     * dbo.USERS.USERNAME.
     */
    private User requireUser(
            AuthenticatedUser requester) {

        if (requester == null
                || requester.username() == null
                || requester.username().isBlank()) {

            throw ApiException.unauthorized(
                    "Authentication required."
            );
        }

        return userRepository
                .findFirstByUsernameIgnoreCase(
                        requester.username().trim()
                )
                .orElseThrow(
                        () ->
                                ApiException.unauthorized(
                                        "Unknown user."
                                )
                );
    }

    /**
     * Vérifie que l'utilisateur appartient
     * à la conversation.
     */
    private ConversationParticipant requireMembership(
            Conversation conversation,
            User user) {

        return participantRepository
                .findByConversationAndUser(
                        conversation,
                        user
                )
                .orElseThrow(
                        () ->
                                ApiException.forbidden(
                                        "You are not a member of this conversation."
                                )
                );
    }

    /**
     * Conversion Conversation -> DTO.
     */
    private ConversationResponse toResponse(
            Conversation conversation,
            User me,
            ConversationParticipant myParticipant) {

        List<ConversationParticipant> participants =
                participantRepository
                        .findByConversation(
                                conversation
                        );

        List<String> memberNames =
                participants
                        .stream()
                        .map(
                                ConversationParticipant::getUser
                        )
                        .filter(user ->
                                user != null
                        )
                        .map(User::getName)
                        .toList();

        String displayName;

        if ("GROUP".equals(
                conversation.getType())) {

            displayName =
                    conversation.getName();

        } else {

            displayName =
                    participants
                            .stream()
                            .map(
                                    ConversationParticipant::getUser
                            )
                            .filter(user ->
                                    user != null
                                            && user.getId() != null
                            )
                            .filter(user ->
                                    !user.getId().equals(
                                            me.getId()
                                    )
                            )
                            .map(User::getName)
                            .findFirst()
                            .orElse(
                                    conversation.getName()
                            );
        }

        Optional<ChatMessage> lastMessage =
                messageRepository
                        .findTopByConversationOrderBySentAtDesc(
                                conversation
                        );

        LocalDateTime since =
                myParticipant.getLastReadAt() != null
                        ? myParticipant.getLastReadAt()
                        : conversation.getCreatedAt();

        long unread =
                messageRepository
                        .countByConversationAndSenderNotAndSentAtAfter(
                                conversation,
                                me,
                                since
                        );

        return new ConversationResponse(
                conversation.getConversationId(),
                conversation.getType(),
                displayName,
                memberNames,
                lastMessage
                        .map(ChatMessage::getContent)
                        .orElse(null),
                lastMessage
                        .map(ChatMessage::getSentAt)
                        .orElse(null),
                unread
        );
    }

    /**
     * Conversion ChatMessage -> DTO.
     *
     * Le champ historiquement appelé username
     * contient maintenant USERS.USERNAME.
     */
    private ChatMessageResponse toResponse(
            ChatMessage message,
            User me) {

        User sender =
                message.getSender();

        String senderUsername =
                sender != null
                        ? sender.getUsername()
                        : null;

        String senderName =
                sender != null
                        ? sender.getName()
                        : null;

        boolean mine =
                sender != null
                        && sender.getId() != null
                        && me != null
                        && me.getId() != null
                        && sender.getId().equals(
                        me.getId()
                );

        return new ChatMessageResponse(
                message.getMessageId(),
                message.getConversation()
                        .getConversationId(),
                senderUsername,
                senderName,
                sender != null ? userProfileRepository.findByUser(sender).map(com.ecobank.rccportal.model.UserProfile::getPhotoUrl).orElse(null) : null,
                message.getContent(),
                message.getMediaUrl(),
                message.getExpiresAt(),
                message.getSentAt(),
                mine
        );
    }
}
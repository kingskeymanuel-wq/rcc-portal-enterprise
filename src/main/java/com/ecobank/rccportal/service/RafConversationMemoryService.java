package com.ecobank.rccportal.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mémoire conversationnelle de RAF — volontairement en mémoire process (pas de table SQL) :
 * une conversation RAF est un fil de discussion court-terme pour clarifier une question
 * ("Et pour Android ?"), pas un historique à conserver. Perdue si l'application redémarre
 * ou si l'entrée expire — comportement voulu, pas une limitation à corriger.
 *
 * Une conversation est identifiée par le username de l'agent (un seul fil RAF actif par
 * agent à la fois, ce qui correspond à l'usage réel du widget flottant).
 */
@Service
public class RafConversationMemoryService {

    /** Nombre d'échanges (question + réponse) conservés par conversation. */
    private static final int MAX_TURNS = 6;

    /** Une conversation inactive depuis ce délai est considérée terminée. */
    private static final long TTL_MINUTES = 30;

    public record Turn(String question, String answer, Instant at) {
    }

    private record Conversation(Deque<Turn> turns, Instant lastActivity) {
    }

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    /** Historique récent (le plus ancien en premier) pour l'utilisateur donné, vide si aucun. */
    public Deque<Turn> history(String username) {
        if (username == null) {
            return new ArrayDeque<>();
        }
        Conversation conversation = conversations.get(username);
        if (conversation == null || isExpired(conversation)) {
            return new ArrayDeque<>();
        }
        return conversation.turns();
    }

    /** Enregistre un échange question/réponse, en purgeant les plus anciens au-delà de MAX_TURNS. */
    public void record(String username, String question, String answer) {
        if (username == null) {
            return;
        }
        conversations.compute(username, (key, existing) -> {
            Deque<Turn> turns = (existing == null || isExpired(existing)) ? new ArrayDeque<>() : existing.turns();
            turns.addLast(new Turn(question, answer, Instant.now()));
            while (turns.size() > MAX_TURNS) {
                turns.removeFirst();
            }
            return new Conversation(turns, Instant.now());
        });
    }

    /** Efface le fil de conversation d'un utilisateur (nouvelle conversation explicite). */
    public void clear(String username) {
        if (username != null) {
            conversations.remove(username);
        }
    }

    private boolean isExpired(Conversation conversation) {
        return conversation.lastActivity().isBefore(Instant.now().minusSeconds(TTL_MINUTES * 60));
    }

    /** Purge périodique des conversations expirées pour éviter une fuite mémoire à long terme. */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void evictExpired() {
        conversations.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }
}

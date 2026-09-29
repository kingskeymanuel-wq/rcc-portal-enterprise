package com.ecobank.rccportal.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mémoire conversationnelle de RAF. Le contexte de dialogue (6 derniers échanges, 30 min) reste en mémoire pour
 * la rapidité, mais chaque échange est aussi écrit dans dbo.RafTurns : la conversation du jour réapparaît quand
 * l'agent change de page, et survit à un redémarrage ou une mise à jour du serveur (IntelliJ) — rien ne revient
 * à zéro. Les suites de chiffres (comptes, cartes, téléphones) sont masquées avant écriture ; conservation 7 jours.
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

    /** Contexte de dialogue RAF (mode guidé, choix proposés, dernière question...) — même durée de vie. */
    private record StateHolder(com.ecobank.rccportal.raf.RafModels.RafDialogueState state, Instant lastActivity) {
    }

    private final Map<String, StateHolder> states = new ConcurrentHashMap<>();

    /** Absent dans les tests unitaires : la mémoire reste alors purement en mémoire process. */
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Numéros de compte, de carte, de téléphone… : jamais écrits en clair dans l'historique. */
    static String mask(String text) {
        if (text == null) return null;
        String t = text.replaceAll("\\d(?:[ .-]?\\d){5,}", "••••••");
        return t.length() > 4000 ? t.substring(0, 4000) : t;
    }

    /** Échanges du jour (le plus ancien en premier, 20 au plus) — pour réafficher le fil dans le widget. */
    public java.util.List<Turn> today(String username) {
        if (username == null || jdbc == null) return new java.util.ArrayList<>(history(username));
        try {
            java.util.List<Turn> out = new java.util.ArrayList<>();
            jdbc.query("SELECT TOP 20 Question, Answer, At FROM dbo.RafTurns WHERE Username = ? AND Cleared = 0 "
                    + "AND At >= CAST(CAST(SYSUTCDATETIME() AS DATE) AS DATETIME2) ORDER BY At DESC, TurnId DESC", rs -> {
                out.add(0, new Turn(rs.getString("Question"), rs.getString("Answer"), rs.getTimestamp("At").toInstant()));
            }, username.toLowerCase());
            return out;
        } catch (RuntimeException e) {
            return new java.util.ArrayList<>(history(username));
        }
    }

    /** Après un redémarrage, le contexte récent (moins de 30 min) est relu depuis la base. */
    private Deque<Turn> reload(String username) {
        Deque<Turn> turns = new ArrayDeque<>();
        if (jdbc == null) return turns;
        try {
            jdbc.query("SELECT TOP " + MAX_TURNS + " Question, Answer, At FROM dbo.RafTurns WHERE Username = ? AND Cleared = 0 "
                    + "AND At >= DATEADD(minute, -" + TTL_MINUTES + ", SYSUTCDATETIME()) ORDER BY At DESC, TurnId DESC", rs -> {
                turns.addFirst(new Turn(rs.getString("Question"), rs.getString("Answer"), rs.getTimestamp("At").toInstant()));
            }, username.toLowerCase());
            if (!turns.isEmpty()) conversations.put(username, new Conversation(turns, turns.getLast().at()));
        } catch (RuntimeException ignored) {
            // table pas encore créée
        }
        return turns;
    }

    public com.ecobank.rccportal.raf.RafModels.RafDialogueState state(String username) {
        if (username == null) return com.ecobank.rccportal.raf.RafModels.RafDialogueState.empty();
        StateHolder holder = states.get(username);
        if (holder == null || holder.lastActivity().isBefore(Instant.now().minusSeconds(TTL_MINUTES * 60))) {
            return com.ecobank.rccportal.raf.RafModels.RafDialogueState.empty();
        }
        return holder.state();
    }

    public void saveState(String username, com.ecobank.rccportal.raf.RafModels.RafDialogueState state) {
        if (username == null || state == null) return;
        states.put(username, new StateHolder(state, Instant.now()));
    }

    /** Historique récent (le plus ancien en premier) pour l'utilisateur donné, vide si aucun. */
    public Deque<Turn> history(String username) {
        if (username == null) {
            return new ArrayDeque<>();
        }
        Conversation conversation = conversations.get(username);
        if (conversation == null) {
            return reload(username);
        }
        if (isExpired(conversation)) {
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
        if (jdbc != null) {
            try {
                jdbc.update("INSERT INTO dbo.RafTurns (Username, Question, Answer) VALUES (?, ?, ?)",
                        username.toLowerCase(), mask(question), mask(answer));
            } catch (RuntimeException ignored) {
                // table pas encore créée : la mémoire reste en process
            }
        }
    }

    /** Efface le fil de conversation d'un utilisateur (nouvelle conversation explicite). */
    public void clear(String username) {
        if (username != null) {
            conversations.remove(username);
            states.remove(username);
            if (jdbc != null) {
                try {
                    jdbc.update("UPDATE dbo.RafTurns SET Cleared = 1 WHERE Username = ? AND Cleared = 0", username.toLowerCase());
                } catch (RuntimeException ignored) {
                    // table pas encore créée
                }
            }
        }
    }

    private boolean isExpired(Conversation conversation) {
        return conversation.lastActivity().isBefore(Instant.now().minusSeconds(TTL_MINUTES * 60));
    }

    /** Purge périodique des conversations expirées pour éviter une fuite mémoire à long terme. */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void evictExpired() {
        conversations.entrySet().removeIf(entry -> isExpired(entry.getValue()));
        states.entrySet().removeIf(entry -> entry.getValue().lastActivity().isBefore(Instant.now().minusSeconds(TTL_MINUTES * 60)));
        if (jdbc != null) {
            try {
                jdbc.update("DELETE FROM dbo.RafTurns WHERE At < DATEADD(day, -7, SYSUTCDATETIME())");
            } catch (RuntimeException ignored) {
                // table pas encore créée
            }
        }
    }
}

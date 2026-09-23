package com.ecobank.rccportal.raf;

import com.ecobank.rccportal.dto.RalphResultItem;
import com.ecobank.rccportal.dto.RafAction;
import com.ecobank.rccportal.dto.RafSuggestion;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Types partagés par le routeur, les agents et l'orchestrateur RAF. */
public final class RafModels {

    private RafModels() {
    }

    public enum RafIntent {
        CALL_CARD, PROCEDURE, SLA, GLOSSARY, BRANCH, MY_SHIFT, TEMPLATE, VERIFIED_QA, KNOWLEDGE, SMALL_TALK, HELP
    }

    /** Éléments repérés dans la question (pays, ville, date, étape, niveau N1/N2, champs d'un modèle). */
    public record RafEntities(String countryCode, String city, LocalDate date, Integer stepNumber, String level,
                              java.util.Map<String, String> slotValues) {

        public static RafEntities empty() {
            return new RafEntities(null, null, null, null, null, java.util.Map.of());
        }

        /** Les valeurs de cette question l'emportent ; les manquantes sont reprises de la précédente. */
        public RafEntities mergeOver(RafEntities previous) {
            if (previous == null) return this;
            return new RafEntities(
                    countryCode != null ? countryCode : previous.countryCode(),
                    city != null ? city : previous.city(),
                    date != null ? date : previous.date(),
                    stepNumber != null ? stepNumber : previous.stepNumber(),
                    level != null ? level : previous.level(),
                    slotValues.isEmpty() ? previous.slotValues() : slotValues);
        }
    }

    /**
     * Une question prête à être traitée : déjà assainie (DataProtectionService), normalisée et
     * enrichie. {@code command} = action déterministe issue d'un bouton du widget
     * (« raf:proc:12:step:3 »...) ou d'une relance reconnue (« suivant », « 2 »...).
     */
    public record RafRequest(String question, String normalized, List<String> terms, String lang, String username,
                             RafEntities entities, RafDialogueState state, String command, LocalDateTime now) {

        public RafRequest withQuestion(String newQuestion, String newNormalized, List<String> newTerms, RafEntities newEntities) {
            return new RafRequest(newQuestion, newNormalized, newTerms, lang, username, newEntities, state, command, now);
        }

        public RafRequest withCommand(String newCommand) {
            return new RafRequest(question, normalized, terms, lang, username, entities, state, newCommand, now);
        }
    }

    public record IntentScore(RafIntent intent, double score, List<String> evidence) {
    }

    /** Mode guidé en cours : procédure et étape affichée (1..n). */
    public record GuidedSession(int procedureId, int step) {
    }

    /** Mémoire de dialogue d'un agent (courte durée, en mémoire uniquement). */
    public record RafDialogueState(RafIntent lastIntent, String lastQuestion, RafEntities lastEntities,
                                   String lastDetails, GuidedSession guided, List<RafSuggestion> pendingChoices) {

        public static RafDialogueState empty() {
            return new RafDialogueState(null, null, RafEntities.empty(), null, null, List.of());
        }
    }

    /**
     * Réponse d'un agent. {@code matchScore} (0..1) = qualité de sa meilleure correspondance ;
     * {@code found=false} = rien à proposer (réponse écartée). Chaque affirmation est citée.
     */
    public record AgentAnswer(String agentId, RafIntent intent, double matchScore, boolean found, String markdown,
                              String details, List<RalphResultItem> citations, List<RafSuggestion> suggestions,
                              RafAction action, GuidedSession guided, List<String> reasoning) {

        public static AgentAnswer notFound(String agentId, RafIntent intent, String reason) {
            return new AgentAnswer(agentId, intent, 0, false, null, null, List.of(), List.of(), null, null, List.of(reason));
        }

        public AgentAnswer withMatchScore(double score) {
            return new AgentAnswer(agentId, intent, score, found, markdown, details, citations, suggestions, action, guided, reasoning);
        }
    }
}

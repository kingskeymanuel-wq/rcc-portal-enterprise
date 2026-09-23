package com.ecobank.rccportal.util;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lecture d'une journée de pointage (événements ShiftEvents d'un agent, un jour donné) :
 * état courant, heure de début de cet état, segments (travail, pause, déconnexion...) et
 * absences pour déconnexion.
 *
 * <p><b>Déconnexion / reconnexion le même jour</b> : une déconnexion (bouton Déconnexion,
 * sans « Fin de shift ») enregistre un événement {@code LOGOUT} — dernière heure de
 * déconnexion retenue. À la reconnexion ({@code LOGIN}) le même jour, l'agent retrouve l'état
 * qu'il avait avant de partir (en poste, en pause...) et le minuteur <b>reprend en continuité</b>
 * depuis le début de cet état, absence comprise — l'absence est tracée à part
 * ({@link #absences()}), jamais comptée comme temps travaillé.</p>
 *
 * <p>Corrige aussi un défaut historique : un deuxième {@code LOGIN} dans la journée
 * (reconnexion) remettait le compteur à zéro et faisait perdre tout le temps travaillé
 * avant la reconnexion dans les calculs.</p>
 */
public final class ShiftTimeline {

    public static final String WORKING = "WORKING";
    public static final String ON_PAUSE = "ON_PAUSE";
    public static final String ON_LUNCH = "ON_LUNCH";
    public static final String ON_TRAINING = "ON_TRAINING";
    public static final String ON_MEETING = "ON_MEETING";
    public static final String DISCONNECTED = "DISCONNECTED";
    public static final String SHIFT_ENDED = "SHIFT_ENDED";
    public static final String NOT_STARTED = "NOT_STARTED";

    /** Un événement brut. */
    public record Event(String type, LocalDateTime at) {
    }

    /** Période continue dans un même état ; {@code to == null} = en cours. */
    public record Segment(String state, LocalDateTime from, LocalDateTime to) {
    }

    /** Absence pour déconnexion ; {@code reconnectedAt == null} = toujours déconnecté. */
    public record Absence(LocalDateTime disconnectedAt, LocalDateTime reconnectedAt) {
        public long minutes(LocalDateTime now) {
            LocalDateTime end = reconnectedAt != null ? reconnectedAt : now;
            return end == null ? 0 : Math.max(0, Duration.between(disconnectedAt, end).toMinutes());
        }
    }

    private final String state;
    private final LocalDateTime stateSince;
    private final List<Segment> segments;
    private final List<Absence> absences;

    private ShiftTimeline(String state, LocalDateTime stateSince, List<Segment> segments, List<Absence> absences) {
        this.state = state;
        this.stateSince = stateSince;
        this.segments = segments;
        this.absences = absences;
    }

    public static ShiftTimeline of(List<Event> events) {
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(Event::at));

        String state = NOT_STARTED;
        LocalDateTime since = null;
        String stateBeforeLogout = null;
        LocalDateTime sinceBeforeLogout = null;
        List<Segment> segments = new ArrayList<>();
        List<Absence> absences = new ArrayList<>();
        LocalDateTime segStart = null; // début du segment physique en cours

        for (Event e : sorted) {
            String type = e.type() == null ? "" : e.type();
            LocalDateTime at = e.at();
            switch (type) {
                case "LOGIN" -> {
                    if (DISCONNECTED.equals(state)) {
                        closeSegment(segments, state, segStart, at);
                        closeAbsence(absences, at);
                        // Reprise en continuité : même état et même heure de début qu'avant la déconnexion.
                        state = stateBeforeLogout;
                        since = sinceBeforeLogout;
                        segStart = at;
                    } else if (NOT_STARTED.equals(state) || SHIFT_ENDED.equals(state)) {
                        state = WORKING;
                        since = at;
                        segStart = at;
                    }
                    // Sinon (reconnexion sans déconnexion enregistrée : session expirée, onglet
                    // fermé...) : on garde l'état et le minuteur en cours, sans rien écraser.
                }
                case "LOGOUT" -> {
                    if (!NOT_STARTED.equals(state) && !SHIFT_ENDED.equals(state) && !DISCONNECTED.equals(state)) {
                        closeSegment(segments, state, segStart, at);
                        stateBeforeLogout = state;
                        sinceBeforeLogout = since;
                        state = DISCONNECTED;
                        since = at;
                        segStart = at;
                        absences.add(new Absence(at, null));
                    }
                }
                default -> {
                    String next = stateAfter(type);
                    if (next == null) continue; // type inconnu : ignoré
                    if (DISCONNECTED.equals(state)) closeAbsence(absences, at); // correction manuelle pendant l'absence
                    closeSegment(segments, state, segStart, at);
                    state = next;
                    since = at;
                    segStart = SHIFT_ENDED.equals(next) ? null : at;
                }
            }
        }
        if (segStart != null && !NOT_STARTED.equals(state) && !SHIFT_ENDED.equals(state)) {
            segments.add(new Segment(state, segStart, null));
        }
        return new ShiftTimeline(state, since, List.copyOf(segments), List.copyOf(absences));
    }

    private static String stateAfter(String type) {
        return switch (type) {
            case "PAUSE_START" -> ON_PAUSE;
            case "LUNCH_START" -> ON_LUNCH;
            case "TRAINING_START" -> ON_TRAINING;
            case "MEETING_START" -> ON_MEETING;
            case "PAUSE_END", "LUNCH_END", "TRAINING_END", "MEETING_END" -> WORKING;
            case "SHIFT_END" -> SHIFT_ENDED;
            default -> null;
        };
    }

    private static void closeSegment(List<Segment> segments, String state, LocalDateTime from, LocalDateTime to) {
        if (from == null || NOT_STARTED.equals(state) || SHIFT_ENDED.equals(state)) return;
        if (to.isAfter(from)) segments.add(new Segment(state, from, to));
    }

    private static void closeAbsence(List<Absence> absences, LocalDateTime at) {
        if (!absences.isEmpty() && absences.get(absences.size() - 1).reconnectedAt() == null) {
            Absence open = absences.remove(absences.size() - 1);
            absences.add(new Absence(open.disconnectedAt(), at));
        }
    }

    public String state() {
        return state;
    }

    /** Début de l'état courant — continuité conservée à travers une déconnexion/reconnexion. */
    public LocalDateTime stateSince() {
        return stateSince;
    }

    public List<Segment> segments() {
        return segments;
    }

    public List<Absence> absences() {
        return absences;
    }

    /** Dernière déconnexion de la journée (null si aucune). */
    public LocalDateTime lastDisconnectedAt() {
        return absences.isEmpty() ? null : absences.get(absences.size() - 1).disconnectedAt();
    }

    /** Dernière reconnexion après déconnexion (null si aucune ou si toujours déconnecté). */
    public LocalDateTime lastReconnectedAt() {
        for (int i = absences.size() - 1; i >= 0; i--) {
            if (absences.get(i).reconnectedAt() != null) return absences.get(i).reconnectedAt();
        }
        return null;
    }

    /** Durée totale des absences pour déconnexion ; une absence en cours est comptée jusqu'à {@code now}. */
    public long absenceMinutes(LocalDateTime now) {
        return absences.stream().mapToLong(a -> a.minutes(now)).sum();
    }

    /** Absences survenues depuis le début de l'état courant — incluses dans le minuteur continu. */
    public long absenceMinutesInCurrentState(LocalDateTime now) {
        if (stateSince == null) return 0;
        return absences.stream()
                .filter(a -> !a.disconnectedAt().isBefore(stateSince))
                .mapToLong(a -> a.minutes(now))
                .sum();
    }

    /**
     * Minutes travaillées (en poste, formation, réunion) — déconnexions et pauses exclues.
     * Un segment encore ouvert n'est compté que jusqu'à {@code openEnd} (null = non compté).
     */
    public long workedMinutes(LocalDateTime openEnd) {
        long total = 0;
        for (Segment s : segments) {
            if (!WORKING.equals(s.state()) && !ON_TRAINING.equals(s.state()) && !ON_MEETING.equals(s.state())) continue;
            LocalDateTime end = s.to() != null ? s.to() : openEnd;
            if (end != null && end.isAfter(s.from())) total += Duration.between(s.from(), end).toMinutes();
        }
        return total;
    }
}

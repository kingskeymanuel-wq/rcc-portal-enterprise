package com.ecobank.rccportal.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * Débordement de shift : l'agent est encore en poste (ou en pause, formation, réunion) après la fin prévue
 * de son shift — le plus souvent un oubli de « Fin de shift ». Le temps qui s'écoule s'affiche en jaune
 * (« Débordement ») et la session est clôturée automatiquement {@link #AUTO_CLOSE_MINUTES} minutes après
 * la fin prévue, pour que le dépassement ne s'allonge pas.
 */
public final class ShiftOverflow {

    /** Clôture automatique : 1 h 30 après la fin prévue du shift. */
    public static final int AUTO_CLOSE_MINUTES = 90;

    /** Durée retenue quand l'agent n'a pas de planning ce jour-là : à partir de sa première connexion. */
    public static final int DEFAULT_SHIFT_HOURS = 9;

    /** États où l'agent est encore « dans » son shift (il ne l'a pas terminé). */
    public static final Set<String> ACTIVE_STATES = Set.of(ShiftTimeline.WORKING, ShiftTimeline.ON_PAUSE,
            ShiftTimeline.ON_LUNCH, ShiftTimeline.ON_TRAINING, ShiftTimeline.ON_MEETING);

    private ShiftOverflow() {
    }

    /**
     * Fin prévue du shift du jour : planning (une fin avant le début = shift de nuit, fin le lendemain),
     * sinon première connexion + {@code defaultHours} ; {@code null} sans planning ni connexion.
     */
    public static LocalDateTime plannedEnd(LocalDate day, LocalTime plannedStart, LocalTime plannedEnd,
                                           LocalDateTime firstLogin, int defaultHours) {
        if (plannedEnd != null) {
            LocalDateTime end = day.atTime(plannedEnd);
            if (plannedStart != null && !plannedEnd.isAfter(plannedStart)) end = end.plusDays(1);
            return end;
        }
        if (firstLogin == null) return null;
        return firstLogin.plusHours(defaultHours > 0 ? defaultHours : DEFAULT_SHIFT_HOURS);
    }

    public static boolean isActive(String state) {
        return state != null && ACTIVE_STATES.contains(state);
    }

    /** En débordement : shift toujours ouvert après sa fin prévue. */
    public static boolean overflowing(String state, LocalDateTime plannedEnd, LocalDateTime now) {
        return isActive(state) && plannedEnd != null && now.isAfter(plannedEnd);
    }

    public static LocalDateTime autoCloseAt(LocalDateTime plannedEnd) {
        return plannedEnd == null ? null : plannedEnd.plusMinutes(AUTO_CLOSE_MINUTES);
    }

    /** Minutes de débordement (0 hors débordement). */
    public static long overflowMinutes(String state, LocalDateTime plannedEnd, LocalDateTime now) {
        return overflowing(state, plannedEnd, now) ? java.time.Duration.between(plannedEnd, now).toMinutes() : 0;
    }
}

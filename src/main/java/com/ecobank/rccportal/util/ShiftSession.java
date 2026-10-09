package com.ecobank.rccportal.util;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Function;

/**
 * Session de shift : ce qui sépare un shift du suivant n'est plus minuit mais la « Fin de shift » (cliquée par
 * l'agent, ou posée automatiquement à la fin prévue + débordement, voir ShiftOverflow). Un shift de nuit
 * (21h-07h) reste donc un seul shift, et le lendemain un nouveau shift ne démarre qu'à la connexion de l'agent,
 * selon son planning.
 */
public final class ShiftSession {

    /** Fenêtre lue pour retrouver le shift en cours : couvre un shift de nuit et son débordement. */
    public static final int LOOKBACK_HOURS = 30;

    /** Connexion acceptée jusqu'à 2 h avant le début prévu du shift. */
    public static final int EARLY_LOGIN_HOURS = 2;

    private ShiftSession() {
    }

    /**
     * Événements du shift en cours (après la dernière « Fin de shift »). Si le dernier événement est une
     * « Fin de shift », c'est le shift qui vient de se terminer (de la fin de shift précédente à celle-ci).
     */
    public static <E> List<E> current(List<E> events, Function<E, String> type) {
        if (events.isEmpty()) return events;
        int last = events.size() - 1;
        int stop = "SHIFT_END".equals(type.apply(events.get(last))) ? last - 1 : last;
        int from = 0;
        for (int i = stop; i >= 0; i--) {
            if ("SHIFT_END".equals(type.apply(events.get(i)))) { from = i + 1; break; }
        }
        return events.subList(from, events.size());
    }

    /**
     * Une connexion après un shift terminé ouvre-t-elle un nouveau shift ? Oui pour le shift suivant du planning
     * (à partir de 2 h avant son début), jamais pour le shift qui vient de se terminer. Sans shift travaillé au
     * planning ce jour-là : seulement un autre jour que celui du shift terminé.
     *
     * @param endedShiftStart début du shift terminé (premier événement)
     * @param plannedStart    début prévu du shift du jour au planning, null si repos / congé / aucun planning
     */
    public static boolean mayStartNewShift(LocalDateTime endedShiftStart, LocalDateTime now, LocalTime plannedStart) {
        if (endedShiftStart == null) return true;
        if (plannedStart != null) {
            LocalDateTime opens = now.toLocalDate().atTime(plannedStart).minusHours(EARLY_LOGIN_HOURS);
            return endedShiftStart.isBefore(opens) && !now.isBefore(opens);
        }
        return !endedShiftStart.toLocalDate().equals(now.toLocalDate());
    }
}

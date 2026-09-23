package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.raf.RafDocs.SlaDoc;
import com.ecobank.rccportal.util.SearchText;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Échéance à annoncer au client à partir d'une règle SLA officielle (table SlaRules) — jamais
 * d'estimation : si le libellé est conditionnel ou ambigu, aucune date n'est calculée et seul
 * le libellé officiel est affiché.
 */
public final class SlaCalculator {

    private SlaCalculator() {
    }

    public static Optional<LocalDateTime> dueDate(LocalDateTime from, SlaDoc rule) {
        if (rule == null || rule.slaHours() == null || rule.slaHours() <= 0 || rule.slaLabel() == null) return Optional.empty();
        String label = " " + SearchText.normalize(rule.slaLabel()) + " ";
        if (rule.slaLabel().length() > 40 || label.contains(" si ") || label.contains(" selon ") || label.contains("instantan")
                || label.contains("immediat")) {
            return Optional.empty();
        }
        int hours = rule.slaHours();
        if (label.contains("ouvr") || label.contains("business")) {
            int days = Math.max(1, Math.round(hours / 24f));
            LocalDateTime d = from;
            while (isWeekend(d)) d = d.plusDays(1).withHour(8).withMinute(0);
            while (days > 0) {
                d = d.plusDays(1);
                if (!isWeekend(d)) days--;
            }
            return Optional.of(d);
        }
        if (label.contains(" jour") || label.contains(" day")) return Optional.of(from.plusDays(Math.max(1, Math.round(hours / 24f))));
        if (label.contains(" heure") || label.contains(" h ") || label.matches(".*\\d+h.*") || label.contains(" hour")) {
            return Optional.of(from.plusHours(hours));
        }
        return Optional.empty();
    }

    private static boolean isWeekend(LocalDateTime d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}

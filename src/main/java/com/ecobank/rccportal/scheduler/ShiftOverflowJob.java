package com.ecobank.rccportal.scheduler;

import com.ecobank.rccportal.service.ShiftService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chaque minute : clôture les shifts restés ouverts 1 h 30 après leur fin prévue (agent qui a oublié
 * « Fin de shift »). Voir ShiftService.autoCloseOverflows() et util.ShiftOverflow.
 */
@Slf4j
@Component
public class ShiftOverflowJob {

    private final ShiftService shiftService;

    public ShiftOverflowJob(ShiftService shiftService) {
        this.shiftService = shiftService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void closeForgottenShifts() {
        try {
            int n = shiftService.autoCloseOverflows();
            if (n > 0) log.info("Débordement : {} shift(s) clôturé(s) automatiquement.", n);
        } catch (RuntimeException e) {
            log.warn("Débordement : clôture automatique en échec ({}).", e.getMessage());
        }
    }
}

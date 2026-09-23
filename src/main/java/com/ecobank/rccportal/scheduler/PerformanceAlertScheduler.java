package com.ecobank.rccportal.scheduler;

import com.ecobank.rccportal.service.AlertEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * Détection automatique d'anomalies de performance, chaque nuit — équivalent du
 * "DailyAnalyticsScheduler" décrit par l'utilisateur (analyse quotidienne à 23h sans
 * dépendance à un fournisseur d'IA externe). Analyse le mois en cours ; les alertes déjà
 * émises pour un agent/mois/type ne sont jamais recréées (voir AlertEngineService.raise()).
 */
@Slf4j
@Component
public class PerformanceAlertScheduler {

    private final AlertEngineService alertEngineService;

    public PerformanceAlertScheduler(AlertEngineService alertEngineService) {
        this.alertEngineService = alertEngineService;
    }

    @Scheduled(cron = "0 0 23 * * *") // tous les jours à 23h00
    public void runNightlyDetection() {
        int created = alertEngineService.detectForMonth(YearMonth.now());
        if (created > 0) {
            log.info("[AlertEngine] {} nouvelle(s) alerte(s) de performance détectée(s) pour {}.", created, YearMonth.now());
        }
    }
}

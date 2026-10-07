package com.ecobank.rccportal.config;

import com.ecobank.rccportal.service.DataPatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Applique en base, une seule fois, les correctifs de données pas encore appliqués (voir DataPatchService).
 * Un correctif déjà appliqué n'est jamais rejoué au démarrage : ce que l'administrateur modifie ensuite reste.
 * En cas d'échec, rien n'est marqué comme appliqué : nouvel essai au prochain démarrage, ou « Réappliquer »
 * depuis Administration.
 */
@Slf4j
@Component
@Order(30) // après le schéma (Order 1), les comptes et les référentiels (Order 20 à 25)
public class DataPatchBootstrap implements CommandLineRunner {

    private final DataPatchService patches;
    private final com.ecobank.rccportal.service.ShiftCatalogService shiftCatalog;

    public DataPatchBootstrap(DataPatchService patches, com.ecobank.rccportal.service.ShiftCatalogService shiftCatalog) {
        this.patches = patches;
        this.shiftCatalog = shiftCatalog;
    }

    @Override
    public void run(String... args) {
        try {
            shiftCatalog.ensure(); // catalogue des shifts (M, M2, M3, M4, A, N) — jamais d'écrasement d'un horaire modifié
        } catch (RuntimeException e) {
            log.error("[SHIFTS] Table SHIFT_CODES indisponible : {}", e.getMessage());
        }
        try {
            patches.ensureTable();
        } catch (RuntimeException e) {
            log.error("[DATA PATCH] Table DATA_PATCHES indisponible : {}", e.getMessage());
            return;
        }
        for (DataPatchService.Patch p : DataPatchService.PATCHES) {
            try {
                if (!patches.isApplied(p.code())) patches.run(p.code(), "système (démarrage)");
            } catch (RuntimeException e) {
                log.error("[DATA PATCH] Échec de {} : {}", p.code(), e.getMessage(), e);
            }
        }
    }
}

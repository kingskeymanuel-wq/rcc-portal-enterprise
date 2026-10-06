package com.ecobank.rccportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RccPortalApplication {

    /** Heure de référence du portail : Côte d'Ivoire (GMT, sans heure d'été), quel que soit le réglage du serveur. */
    public static final String PORTAL_TIME_ZONE = "Africa/Abidjan";

    public static void main(String[] args) {
        // Avant tout le reste : pointages, retards, débordements, plannings et journaux sont calculés à l'heure
        // ivoirienne, même si la machine qui héberge l'application est réglée sur un autre fuseau.
        // RCC_TIMEZONE permet de changer ce fuseau sans recompiler (déconseillé : les postes restent en GMT).
        String zone = System.getenv().getOrDefault("RCC_TIMEZONE", PORTAL_TIME_ZONE);
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(java.time.ZoneId.of(zone)));
        System.setProperty("user.timezone", zone);
        SpringApplication.run(RccPortalApplication.class, args);
    }
}

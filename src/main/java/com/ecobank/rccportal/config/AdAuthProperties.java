package com.ecobank.rccportal.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Lit rcc.auth.ad.* depuis application.yml — authentification réelle contre l'Active
 * Directory Ecobank via le service SOAP interne rechercherUserAD (host uniquement
 * joignable depuis le réseau interne Ecobank). Désactivé si url vide : dans ce cas
 * AuthService.initiateLogin lève ad_disabled (aucune vérification locale n'existe).
 *
 * IMPORTANT : la valeur de successStatus est une hypothèse ("OK") non confirmée avec
 * l'IT Ecobank au moment de l'écriture — à vérifier/ajuster avant tout déploiement réel
 * sur le réseau Ecobank, sans quoi l'intégration risque de rejeter (ou pire, d'accepter)
 * systématiquement les authentifications AD.
 */
@Getter @Setter
@Configuration
@ConfigurationProperties(prefix = "rcc.auth.ad")
public class AdAuthProperties {

    /** URL complète du endpoint SOAP (ex. https://epg-eci-apps01/RIB_DELIVERY/RIB_DELIVRY.asmx). Vide = AD désactivé. */
    private String url = "";

    /** Valeur de <Status> dans la réponse qui signifie "authentification réussie" — À CONFIRMER avec l'IT Ecobank. */
    private String successStatus = "OK";

    /** Délai max d'attente de la réponse SOAP, en millisecondes. */
    private int timeoutMs = 5000;

    /**
     * Désactive la validation du certificat SSL pour l'appel AD — nécessaire tant
     * que la CA interne Ecobank n'est pas importée dans le trust store du JDK
     * (erreur "PKIX path building failed" sur certificat auto-signé / CA interne).
     * À passer à false une fois keytool -importcert effectué.
     */
    private boolean insecureSsl = false;

    public boolean isEnabled() {
        return url != null && !url.isBlank();
    }
}
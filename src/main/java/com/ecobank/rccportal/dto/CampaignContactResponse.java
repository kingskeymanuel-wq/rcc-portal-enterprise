package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record CampaignContactResponse(
        Integer contactId,
        Integer campaignId,
        String campaignName,
        Long agentUserId,
        String agentName,
        String clientName,
        String clientPhone,
        String maskedAccountNumber,
        String callStatus,
        String notes,
        /** Réponses aux questions dynamiques de la campagne — { fieldId: valeur }. */
        Map<String, String> answers,
        /** Colonnes du fichier importé sans correspondance connue — { en-tête original: valeur }.
         *  Lecture seule : affichage de référence pour l'agent, jamais modifiable depuis l'appel. */
        Map<String, String> extraData,
        LocalDateTime lastCalledAt,
        Integer appointmentId
) {
}

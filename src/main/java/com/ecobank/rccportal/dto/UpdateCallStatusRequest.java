package com.ecobank.rccportal.dto;

import java.util.Map;

public record UpdateCallStatusRequest(
        String callStatus,
        String notes,
        /** Réponses aux questions dynamiques de la campagne — { fieldId: valeur }, optionnel. */
        Map<String, String> answers
) {
}

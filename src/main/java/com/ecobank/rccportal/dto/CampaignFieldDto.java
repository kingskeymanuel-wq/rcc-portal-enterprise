package com.ecobank.rccportal.dto;

import java.util.List;

/**
 * Une question du modèle de campagne (voir Campaign.fieldsJson) — affichée dynamiquement dans
 * la modale d'appel agent, en plus des 4 boutons de statut fixes.
 * type : "TEXT" | "TEXTAREA" | "SELECT" | "RADIO" | "DATE" | "TIME" | "LINK".
 * options : uniquement pour SELECT/RADIO — liste des choix proposés.
 */
public record CampaignFieldDto(
        String id,
        String label,
        String type,
        List<String> options,
        boolean required
) {
}

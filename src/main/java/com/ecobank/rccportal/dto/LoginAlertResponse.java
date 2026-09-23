package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Réclamation/alerte remontée depuis la page de connexion (mot de passe oublié, contact IT,
 * compte verrouillé) — dédupliquée : une même alerte crée une notification par admin en base,
 * regroupée ici en une seule entrée avec la liste des notifications qui la composent (pour
 * pouvoir toutes les marquer traitées d'un coup).
 */
public record LoginAlertResponse(
        String subject, String content, LocalDateTime createdAt, boolean actionable,
        String actionType, String actionTarget, List<Integer> notificationIds
) {
}

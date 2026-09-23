package com.ecobank.rccportal.dto;

import java.util.Map;

/**
 * Élément interactif affiché par le widget RAF sous la réponse.
 * type : GUIDED_STEP (mode guidé d'une procédure), SLA_DUE (échéance calculée), DRAFT (modèle
 * de message pré-rempli), CALL_CARD (fiche appel).
 */
public record RafAction(String type, Map<String, Object> payload) {
}

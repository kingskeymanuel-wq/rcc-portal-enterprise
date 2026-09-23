package com.ecobank.rccportal.dto;

/**
 * needsSetup : conservé pour compatibilité avec les appelants existants, toujours false —
 * l'écran de première connexion (choix filiale/service/équipe) a été retiré du projet, accès
 * direct au portail dans tous les cas.
 * redirectTo : portail vers lequel rediriger automatiquement (service/rôle métier attribué,
 * ou "/outbound-dashboard" si l'agent est classé Outbound — voir TeamClassifier), sinon null.
 */
public record TeamStatusResponse(boolean needsSetup, String redirectTo) {
}

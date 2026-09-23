package com.ecobank.rccportal.dto;

import java.util.List;

/**
 * Liste des onglets explicitement INTERDITS pour l'utilisateur courant (équipe
 * ou rôle). Modèle "opt-out" : tout onglet absent de cette liste est autorisé
 * par défaut — cohérent avec eco_tab_perms côté frontend (restriction ciblée,
 * pas allowlist exhaustive).
 */
public record AllowedTabsResponse(List<String> deniedTabCodes) {
}

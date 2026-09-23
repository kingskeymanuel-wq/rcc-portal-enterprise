package com.ecobank.rccportal.dto;

import java.util.List;

/**
 * Résultat de l'import "renommage USERNAME" (voir UsernameRemapService) — un fichier
 * Nom complet / Nouvel ID, qui renomme le USERNAME des comptes déjà existants pour
 * les faire correspondre à la nomenclature RH officielle (ex. "aabodout" -> "aabodou").
 * Jamais de création de compte à partir de ce fichier : une ligne dont le nom ne
 * correspond à aucun agent déjà en base est listée dans unresolvedNames, jamais créée.
 */
public record UsernameRemapResult(
        int rowsProcessed,
        int usernamesChanged,
        /** Lignes sans nouvel ID renseigné dans le fichier — laissées telles quelles, jamais bloquantes. */
        int skippedBlankNewId,
        /** Nom de la liste qui ne correspond à aucun compte existant — rien créé, ligne ignorée. */
        List<String> unresolvedNames,
        /** Nouvel ID demandé déjà pris par un AUTRE agent que celui visé par la ligne — bloqué,
         *  à arbitrer manuellement plutôt qu'un suffixe automatique qui masquerait le conflit. */
        List<String> conflicts,
        List<UsernameChangePreview> preview,
        boolean previewTruncated
) {
    public record UsernameChangePreview(String name, String oldUsername, String newUsername) {
    }
}

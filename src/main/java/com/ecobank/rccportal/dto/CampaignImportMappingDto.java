package com.ecobank.rccportal.dto;

import java.util.Map;

/**
 * Mapping colonnes (index 0-based dans le fichier Excel importé) → champs du contact,
 * choisi par l'utilisateur dans la modale de prévisualisation d'import (voir
 * CampaignService.previewImport). Permet d'accepter n'importe quel fichier Excel, quel que
 * soit l'ordre ou le nombre de ses colonnes — plus aucune position de colonne n'est imposée.
 * nameColumn est seul obligatoire ; tous les autres sont nullables (colonne absente de ce
 * fichier, ou fichier sans téléphone du tout — ex. listes d'onboarding — = information non
 * renseignée pour cet import, jamais une erreur).
 * fieldColumns : clé = index de colonne, valeur = id de la question du modèle de campagne
 * (Campaign.fields) à pré-remplir avec le contenu de cette colonne.
 */
public record CampaignImportMappingDto(
        Integer nameColumn,
        Integer phoneColumn,
        Integer accountColumn,
        Integer agentColumn,
        Map<Integer, String> fieldColumns
) {
}

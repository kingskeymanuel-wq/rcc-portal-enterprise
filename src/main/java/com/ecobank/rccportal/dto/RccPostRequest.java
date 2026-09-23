package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

/**
 * Contraintes appliquées uniquement à la création (@Valid n'est posé que sur
 * MonRccController.createPost) — update() reste une mise à jour partielle, champs optionnels.
 *
 * content : texte de la publication — peut être vide SI imageUrl est renseigné (publication
 * image seule, sans légende). Le contrôle "au moins l'un des deux" est fait dans
 * MonRccService.createPost, pas ici par annotation, car @NotBlank interdirait toute
 * publication uniquement composée d'une image.
 *
 * visibilityDays : nombre de jours d'affichage dans le fil principal avant bascule
 * dans l'historique (null = 14 jours par défaut). scheduledFor : date/heure de
 * publication différée (null = publication immédiate) — le post reste invisible,
 * y compris dans l'historique, tant que cette date n'est pas atteinte.
 */
public record RccPostRequest(
        String content,
        String imageUrl,
        Integer visibilityDays,
        LocalDateTime scheduledFor) {
}

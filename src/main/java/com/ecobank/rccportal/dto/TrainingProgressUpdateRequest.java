package com.ecobank.rccportal.dto;

/**
 * scrollPercent/videoWatchedPercent : 0-100, envoyés régulièrement (throttle) par
 * le lecteur de leçon. reportedPlaybackRate sert uniquement à l'audit anti-triche
 * (si > vitesse max autorisée, le serveur ignore l'avancement vidéo de cet envoi
 * et incrémente le compteur de violation, sans jamais faire régresser le pourcentage déjà acquis).
 */
public record TrainingProgressUpdateRequest(
        Integer scrollPercent,
        Integer videoWatchedPercent,
        Double reportedPlaybackRate
) {}

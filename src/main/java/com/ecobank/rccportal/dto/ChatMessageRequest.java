package com.ecobank.rccportal.dto;

/** ephemeralMinutes : durée de vie du message avant disparition (null/0 = pas éphémère). */
public record ChatMessageRequest(
        String content, String mediaUrl, Integer ephemeralMinutes) {
}

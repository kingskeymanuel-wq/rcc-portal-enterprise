package com.ecobank.rccportal.dto;

/** content et imageUrl sont chacun optionnels, mais pas les deux à la fois (vérifié dans le service). */
public record RccStoryRequest(String content, String imageUrl) {
}

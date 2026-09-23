package com.ecobank.rccportal.dto;

import java.util.Map;

/** values : clé = nom de la balise sans les crochets (ex. "NOM"), valeur = texte à insérer. */
public record MailTemplateFillRequest(Map<String, String> values) {
}

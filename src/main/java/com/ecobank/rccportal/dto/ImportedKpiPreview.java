package com.ecobank.rccportal.dto;

import java.math.BigDecimal;

/** Un chiffre réellement importé — agent, rubrique, valeur, période — pour affichage immédiat après import. */
public record ImportedKpiPreview(String agentName, String metricCode, BigDecimal value, String period) {
}

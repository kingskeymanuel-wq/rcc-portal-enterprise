package com.ecobank.rccportal.dto;

/** scoreValue: 0, 1 ou 2 ; null si isNotApplicable = true. */
public record ScoreEntryDto(Integer criterionId, Short scoreValue, boolean isNotApplicable) {
}

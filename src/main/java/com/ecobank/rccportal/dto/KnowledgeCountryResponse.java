package com.ecobank.rccportal.dto;
public record KnowledgeCountryResponse(String countryCode, String label, String flagEmoji, String currency,
                                       String zone, String regulator, String agencyCount, String phone, Integer sortOrder) {}
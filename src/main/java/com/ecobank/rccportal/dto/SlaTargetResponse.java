package com.ecobank.rccportal.dto;

public record SlaTargetResponse(Integer slaTargetId, String team, String type, Integer thresholdHours, boolean custom) {}

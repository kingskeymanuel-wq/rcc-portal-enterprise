package com.ecobank.rccportal.dto;

import java.util.List;

public record QualityCriterionResponse(
        Integer id, String code, String section, String name, String description,
        int weight, boolean isKnockOut, int sortOrder, List<String> attributes) {
}

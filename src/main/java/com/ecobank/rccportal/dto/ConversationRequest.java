package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ConversationRequest(
        @NotBlank(message = "type is required (DM or GROUP).") String type,
        String name,
        @NotEmpty(message = "memberMatricules must contain at least one other member.") List<String> memberMatricules) {
}

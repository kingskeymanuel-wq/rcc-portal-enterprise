package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(
        @NotBlank(message = "Matricule is required.") String matricule,
        @NotBlank(message = "Email is required.") String email,
        @NotBlank(message = "Team is required.") String team,
        @NotBlank(message = "Password is required.") String password) {
}

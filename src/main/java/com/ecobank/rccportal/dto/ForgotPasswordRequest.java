package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(
        @NotBlank String matricule,
        /** Facultatif : si renseigné, le code de réinitialisation est envoyé à cette adresse
         * (qui doit correspondre à l'e-mail Ecobank déjà enregistré sur le compte) plutôt que
         * de demander le code OTP vérifié via le serveur MFA Ecobank — voir AuthService. */
        String email
) {}

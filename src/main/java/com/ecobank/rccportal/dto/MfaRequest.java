package com.ecobank.rccportal.dto;

public record MfaRequest(

        String username,

        String challengeId,

        String otp

) {
}
package com.ecobank.rccportal.dto;

public record TokenResponse(

        String accessToken,

        String refreshToken

) {
}
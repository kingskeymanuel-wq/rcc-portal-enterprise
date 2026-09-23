package com.ecobank.rccportal.dto;

public record GameScoreSubmitRequest(
        Integer score,
        Integer correctCount,
        Integer totalCount
) {}

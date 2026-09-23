package com.ecobank.rccportal.dto;

public record SiteBannerResponse(
        String imageUrl, String headline, String subheadline, String ctaLabel, String ctaUrl) {
}

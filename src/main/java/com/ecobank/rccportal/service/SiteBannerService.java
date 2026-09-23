package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.SiteBannerRequest;
import com.ecobank.rccportal.dto.SiteBannerResponse;
import com.ecobank.rccportal.model.SiteBanner;
import com.ecobank.rccportal.repository.SiteBannerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bannière d'accueil — une seule ligne en base (singleton applicatif).
 * Modifiable par l'administrateur ou le service Quality Assurance
 * (communication) — voir DashboardViewController pour le contrôle d'accès.
 */
@Service
public class SiteBannerService {

    private final SiteBannerRepository repository;
    private final ImageStorageService imageStorageService;

    public SiteBannerService(SiteBannerRepository repository, ImageStorageService imageStorageService) {
        this.repository = repository;
        this.imageStorageService = imageStorageService;
    }

    @Transactional(readOnly = true)
    public SiteBannerResponse get() {
        SiteBanner banner = current();
        return toResponse(banner);
    }

    @Transactional
    public SiteBannerResponse update(SiteBannerRequest request) {
        SiteBanner banner = current();
        if (request.headline() != null) banner.setHeadline(request.headline());
        if (request.subheadline() != null) banner.setSubheadline(request.subheadline());
        if (request.ctaLabel() != null) banner.setCtaLabel(request.ctaLabel());
        if (request.ctaUrl() != null) banner.setCtaUrl(request.ctaUrl());
        return toResponse(repository.save(banner));
    }

    @Transactional
    public SiteBannerResponse updateImage(MultipartFile file) {
        SiteBanner banner = current();
        banner.setImageUrl(imageStorageService.store(file));
        return toResponse(repository.save(banner));
    }

    private SiteBanner current() {
        return repository.findAll().stream().findFirst()
                .orElseGet(() -> repository.save(SiteBanner.builder().build()));
    }

    private SiteBannerResponse toResponse(SiteBanner b) {
        return new SiteBannerResponse(b.getImageUrl(), b.getHeadline(), b.getSubheadline(), b.getCtaLabel(), b.getCtaUrl());
    }
}

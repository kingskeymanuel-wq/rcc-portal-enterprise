package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.SiteBannerRequest;
import com.ecobank.rccportal.dto.SiteBannerResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.SiteBannerService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Bannière d'accueil — lecture ouverte à tous, modification réservée Admin/QA (communication). */
@RestController
@RequestMapping("/api/site-banner")
public class SiteBannerController {

    private final SiteBannerService siteBannerService;

    public SiteBannerController(SiteBannerService siteBannerService) {
        this.siteBannerService = siteBannerService;
    }

    @GetMapping
    public SiteBannerResponse get() {
        return siteBannerService.get();
    }

    @PutMapping
    public SiteBannerResponse update(@RequestBody SiteBannerRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdminOrQa(requester);
        return siteBannerService.update(request);
    }

    @PostMapping(value = "/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public SiteBannerResponse updateImage(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdminOrQa(requester);
        return siteBannerService.updateImage(file);
    }

    private void requireAdminOrQa(AuthenticatedUser requester) {
        boolean isAdmin = requester != null && "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Only an administrator or Quality Assurance can update the homepage banner.");
        }
    }
}

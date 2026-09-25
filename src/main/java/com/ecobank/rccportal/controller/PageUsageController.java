package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.PageUsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Utilisation des onglets : mesure (tout utilisateur connecté) et rapport (administrateur, page Audit). */
@RestController
public class PageUsageController {

    private final PageUsageService usageService;

    public PageUsageController(PageUsageService usageService) {
        this.usageService = usageService;
    }

    @PostMapping("/api/usage/ping")
    public ResponseEntity<Void> ping(@AuthenticationPrincipal AuthenticatedUser requester,
                                     @RequestBody(required = false) PageUsageService.PingRequest request) {
        usageService.record(requester, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/audit/usage")
    public PageUsageService.UsageReport report(@AuthenticationPrincipal AuthenticatedUser requester,
                                               @RequestParam(required = false) Integer days) {
        return usageService.report(requester, days);
    }
}

package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.DashboardResponse;
import com.ecobank.rccportal.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sert les KPI du tableau de bord consommés par static/js/dashboard.js (fetch("/api/dashboard")).
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public DashboardResponse dashboard() {
        return dashboardService.getDashboard();
    }
}
